package com.callback.auth.controller;

import com.callback.auth.model.User;
import com.callback.auth.repository.UserRepository;
import com.callback.auth.security.RsaPrivateKeyLoader;
import com.callback.security.jwt.JwtValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * register / login / me over HTTP through the full security filter chain, against real Postgres
 * (callback_auth DB) and the real RSA keys. Emails are randomised per test and every user a test
 * registers is deleted afterwards. Forged-token variants are covered exhaustively by
 * common-security's JwtValidatorTest; the ones here prove the filter chain actually applies it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtValidator jwtValidator;

    @Autowired
    private ObjectMapper objectMapper;

    // A spy on the real BCrypt bean: proves register hashes with the same encoder login verifies with.
    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private final List<String> emailsToDelete = new CopyOnWriteArrayList<>(); // the race test registers from two threads
    private String email;

    @BeforeEach
    void freshEmail() {
        email = newEmail();
    }

    @AfterEach
    void cleanUp() {
        emailsToDelete.forEach(e -> userRepository.findByEmail(e).ifPresent(userRepository::delete));
    }

    @Test
    void registerReturns201AndStoresABcryptHashFromTheSharedEncoder() throws Exception {
        register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(passwordEncoder).encode(PASSWORD);
        User stored = userRepository.findByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
    }

    @Test
    void registeringTheSameEmailTwiceReturns409() throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());
        register(email, "another-password").andExpect(status().isConflict());
        assertThat(userRepository.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
    }

    @Test
    void concurrentRegistrationsOfTheSameEmailGiveOne201AndOne409() throws Exception {
        // encode() runs between the "already registered?" check and the insert. Holding both
        // requests there until both arrive guarantees both pass the check, so the race happens
        // on every run and the database's unique constraint decides the loser.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothChecked.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> register(email, PASSWORD).andReturn().getResponse().getStatus();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(List.of(attempt, attempt), 30, TimeUnit.SECONDS)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(userRepository.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
    }

    @Test
    void registerRejectsInvalidInputWith400() throws Exception {
        register("not-an-email", PASSWORD).andExpect(status().isBadRequest());
        register("", PASSWORD).andExpect(status().isBadRequest());
        register(email, "short7!").andExpect(status().isBadRequest());
        register(email, "").andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void registerRejectsPasswordsOverBcrypts72ByteLimitWith400NotA500() throws Exception {
        register(email, "a".repeat(73)).andExpect(status().isBadRequest());
        // 40 characters but 80 UTF-8 bytes: within any character limit, still over BCrypt's.
        register(email, "é".repeat(40)).andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void aPasswordOfExactly72BytesRegistersAndLogsIn() throws Exception {
        String password = "é".repeat(36); // 72 bytes
        register(email, password).andExpect(status().isCreated());
        login(email, password).andExpect(status().isOk());
    }

    @Test
    void loginReturnsATokenThatValidatesToTheUsersEmail() throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());

        String body = login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        verify(passwordEncoder).matches(eq(PASSWORD), anyString());
        String token = objectMapper.readTree(body).get("token").asText();
        assertThat(jwtValidator.isValid(token)).isTrue();
        assertThat(jwtValidator.extractEmail(token)).isEqualTo(email);
        assertThat(jwtValidator.extractExpiration(token)).isAfter(Instant.now());
    }

    @Test
    void wrongPasswordAndUnknownEmailBothReturnTheSame401() throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());

        String wrongPassword = login(email, "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        // Same status and body, so login can't be used to find out which emails are registered.
        login(newEmail(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(wrongPassword));
    }

    @Test
    void meReturnsTheEmailForAValidTokenAnd401WithoutOne() throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());
        String token = objectMapper.readTree(login(email, PASSWORD).andReturn().getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(email));
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void meRejectsTamperedAndExpiredTokensSignedByTheRealKey() throws Exception {
        String expired = Jwts.builder()
                .subject(email)
                .issuedAt(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .signWith(RsaPrivateKeyLoader.loadFromClasspath(), Jwts.SIG.RS256)
                .compact();
        String valid = Jwts.builder()
                .subject(email)
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(RsaPrivateKeyLoader.loadFromClasspath(), Jwts.SIG.RS256)
                .compact();
        String[] parts = valid.split("\\.");
        String algNone = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + parts[1] + ".";

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
        for (String rejected : List.of(expired, valid.substring(0, valid.length() - 10) + "AAAAAAAAAA", algNone)) {
            mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + rejected))
                    .andExpect(status().isUnauthorized());
        }
    }

    private ResultActions register(String email, String password) throws Exception {
        emailsToDelete.add(email);
        return post("/auth/register", Map.of("email", email, "password", password));
    }

    private ResultActions login(String email, String password) throws Exception {
        return post("/auth/login", Map.of("email", email, "password", password));
    }

    private ResultActions post(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static String newEmail() {
        return "auth-test-" + UUID.randomUUID() + "@example.com";
    }
}

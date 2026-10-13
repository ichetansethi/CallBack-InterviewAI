package com.callback.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every service trusts JwtValidator alone (no DB lookup), so each forgery here is one any service
 * would otherwise accept. Tokens are signed with a throwaway RSA key pair: the real private key
 * lives only in auth-service. The real public key is covered by the last test.
 */
class JwtValidatorTest {

    private static final String EMAIL = "candidate@example.com";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final KeyPair trusted = rsaKeyPair();
    private final JwtValidator validator = new JwtValidator(trusted.getPublic());

    @Test
    void acceptsATokenSignedWithTheTrustedKey() {
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String token = signed(trusted, EMAIL, expiry);

        assertThat(validator.isValid(token)).isTrue();
        assertThat(validator.extractEmail(token)).isEqualTo(EMAIL);
        assertThat(validator.extractExpiration(token)).isEqualTo(expiry);
    }

    @Test
    void rejectsATokenWhoseSignatureWasTamperedWith() {
        String[] parts = validToken().split("\\.");
        // Change a character mid-signature. The last character can carry only padding bits, so
        // changing it may decode to the same bytes.
        char[] sig = parts[2].toCharArray();
        int i = sig.length / 2;
        sig[i] = sig[i] == 'A' ? 'B' : 'A';

        assertRejected(parts[0] + "." + parts[1] + "." + new String(sig));
    }

    @Test
    void rejectsATokenWhosePayloadWasChangedUnderTheOriginalSignature() {
        String[] parts = validToken().split("\\.");
        String payload = new String(B64URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8)
                .replace(EMAIL, "attacker@example.com");

        assertRejected(parts[0] + "." + b64(payload) + "." + parts[2]);
    }

    @Test
    void rejectsAnExpiredToken() {
        assertRejected(signed(trusted, EMAIL, Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    @Test
    void rejectsATokenSignedWithADifferentRsaKey() {
        // Well-formed RS256, valid expiry; only the signer is wrong.
        assertRejected(signed(rsaKeyPair(), EMAIL, Instant.now().plus(1, ChronoUnit.HOURS)));
    }

    @Test
    void rejectsAlgNoneWithTheSignatureStripped() {
        // Classic attack: take a real token, claim it is unsigned, drop the signature.
        String payload = validToken().split("\\.")[1];
        assertRejected(b64("{\"alg\":\"none\"}") + "." + payload + ".");
    }

    @Test
    void rejectsAlgNoneKeepingTheOriginalSignature() {
        String[] parts = validToken().split("\\.");
        assertRejected(b64("{\"alg\":\"none\"}") + "." + parts[1] + "." + parts[2]);
    }

    @Test
    void rejectsHs256SignedWithThePublicKeyAsTheHmacSecret() {
        // Key confusion: the public key is public, so if a validator let the token's header pick
        // HS256 and used the configured key bytes as the HMAC secret, anyone could forge tokens.
        String forged = Jwts.builder()
                .subject(EMAIL)
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(trusted.getPublic().getEncoded()), Jwts.SIG.HS256)
                .compact();

        assertRejected(forged);
    }

    @Test
    void rejectsMalformedInput() {
        for (String garbage : new String[]{"", "   ", "not-a-jwt", "a.b.c", "a.b"}) {
            assertThat(validator.isValid(garbage)).as("'%s'", garbage).isFalse();
        }
        assertThat(validator.isValid(null)).isFalse();
    }

    @Test
    void defaultConstructorLoadsTheRealPublicKeyFromTheClasspath() {
        // The shipped key verifies nothing signed with our throwaway key, and loads without error.
        assertThat(new JwtValidator().isValid(validToken())).isFalse();
    }

    private void assertRejected(String token) {
        assertThat(validator.isValid(token)).isFalse();
        // Services call extractEmail only after isValid; if one ever skipped that, it must still fail.
        assertThatThrownBy(() -> validator.extractEmail(token)).isInstanceOf(RuntimeException.class);
    }

    private String validToken() {
        return signed(trusted, EMAIL, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    private static String signed(KeyPair keyPair, String subject, Instant expiry) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(Date.from(expiry))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String b64(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

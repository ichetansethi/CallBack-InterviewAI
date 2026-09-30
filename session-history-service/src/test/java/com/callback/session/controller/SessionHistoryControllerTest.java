package com.callback.session.controller;

import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.InterviewSession;
import com.callback.session.model.QuestionFeedback;
import com.callback.session.model.SessionStatus;
import com.callback.session.model.Speaker;
import com.callback.session.model.TranscriptTurn;
import com.callback.session.repository.FeedbackSummaryRepository;
import com.callback.session.repository.InterviewSessionRepository;
import com.callback.session.repository.TranscriptTurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the read side in isolation from the Kafka write path: sessions, turns and feedback are
 * seeded straight through the real repositories into real Postgres (callback_session DB), then
 * read back over HTTP through the full security filter chain. Ownership is exercised via the
 * authenticated principal's name — the same value JwtAuthenticationFilter sets from the token's
 * subject — so JWT validation itself (already covered by common-security) isn't re-tested here.
 *
 * <p>Owner emails are randomised per test so rows left in the shared DB by other runs can never
 * leak into the list assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TranscriptTurnRepository turnRepository;

    @Autowired
    private FeedbackSummaryRepository feedbackRepository;

    private String alice;
    private String bob;
    private InterviewSession aliceOlder;
    private InterviewSession aliceNewer;
    private InterviewSession bobSession;

    @BeforeEach
    void seed() {
        String run = UUID.randomUUID().toString();
        alice = "alice-" + run + "@example.com";
        bob = "bob-" + run + "@example.com";

        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        aliceOlder = sessionRepository.save(new InterviewSession(UUID.randomUUID(), alice, UUID.randomUUID(),
                SessionStatus.ABANDONED, t0, t0.plusSeconds(120)));
        aliceNewer = sessionRepository.save(new InterviewSession(UUID.randomUUID(), alice, UUID.randomUUID(),
                SessionStatus.COMPLETED, t0.plusSeconds(86_400), t0.plusSeconds(86_400 + 900)));
        bobSession = sessionRepository.save(new InterviewSession(UUID.randomUUID(), bob, UUID.randomUUID(),
                SessionStatus.COMPLETED, t0.plusSeconds(3_600), t0.plusSeconds(4_200)));

        // Saved deliberately out of turnIndex order: insertion order must not leak into the response.
        turnRepository.saveAll(List.of(
                new TranscriptTurn(aliceNewer, Speaker.CANDIDATE, "I'd partition by account id.", null, 1),
                new TranscriptTurn(aliceNewer, Speaker.COACH, "How would you scale the ledger writes?",
                        "JD lists Kafka event-driven architecture; resume shows no partitioning experience.", 2),
                new TranscriptTurn(aliceNewer, Speaker.COACH, "Tell me about a system you designed.",
                        "Opening behavioural question.", 0),
                new TranscriptTurn(aliceNewer, Speaker.CANDIDATE, "Using a partition key on the topic.", null, 3),
                new TranscriptTurn(bobSession, Speaker.COACH, "Bob's only question.", "rationale", 0)));

        FeedbackSummary feedback = new FeedbackSummary(aliceNewer, "Clear design answer; partitioning needs depth.", 8, 6, 5);
        // Added out of orderIndex order, like the turns above.
        feedback.setPerQuestion(List.of(
                new QuestionFeedback(feedback, "How would you scale the ledger writes?", "Name the partition key trade-offs.", 1),
                new QuestionFeedback(feedback, "Tell me about a system you designed.", "Good STAR structure.", 0)));
        feedbackRepository.save(feedback);
    }

    @AfterEach
    void cleanUp() {
        List<UUID> ids = List.of(aliceOlder.getId(), aliceNewer.getId(), bobSession.getId());
        ids.forEach(id -> feedbackRepository.findBySessionId(id).ifPresent(feedbackRepository::delete));
        turnRepository.deleteAll(turnRepository.findAll().stream()
                .filter(t -> ids.contains(t.getSession().getId())).toList());
        sessionRepository.deleteAllById(ids);
    }

    @Test
    void getReturnsSessionMetadataWithTranscriptOrderedByTurnIndex() throws Exception {
        mockMvc.perform(get("/sessions/{id}", aliceNewer.getId()).with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceNewer.getId().toString()))
                .andExpect(jsonPath("$.questionSetId").value(aliceNewer.getQuestionSetId().toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startedAt").value("2026-09-02T10:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value("2026-09-02T10:15:00Z"))
                .andExpect(jsonPath("$", not(hasKey("ownerEmail"))))
                .andExpect(jsonPath("$.transcript", hasSize(4)))
                .andExpect(jsonPath("$.transcript[*].turnIndex", contains(0, 1, 2, 3)))
                .andExpect(jsonPath("$.transcript[*].speaker", contains("COACH", "CANDIDATE", "COACH", "CANDIDATE")))
                .andExpect(jsonPath("$.transcript[0].text").value("Tell me about a system you designed."))
                .andExpect(jsonPath("$.transcript[0].questionRationale").value("Opening behavioural question."))
                .andExpect(jsonPath("$.transcript[1].questionRationale", nullValue()))
                .andExpect(jsonPath("$.feedback.overallSummary").value("Clear design answer; partitioning needs depth."))
                .andExpect(jsonPath("$.feedback.clarityScore").value(8))
                .andExpect(jsonPath("$.feedback.structureScore").value(6))
                .andExpect(jsonPath("$.feedback.technicalDepthScore").value(5))
                .andExpect(jsonPath("$.feedback.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.feedback.perQuestion[*].questionText", contains(
                        "Tell me about a system you designed.", "How would you scale the ledger writes?")))
                .andExpect(jsonPath("$.feedback.perQuestion[0].feedbackText").value("Good STAR structure."));
    }

    @Test
    void getReturnsEmptyTranscriptForASessionWithNoTurns() throws Exception {
        mockMvc.perform(get("/sessions/{id}", aliceOlder.getId()).with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABANDONED"))
                .andExpect(jsonPath("$.transcript", hasSize(0)))
                .andExpect(jsonPath("$.feedback", nullValue()));
    }

    @Test
    void getReturns404ForAnotherUsersSessionAndForAMissingOneAlike() throws Exception {
        mockMvc.perform(get("/sessions/{id}", bobSession.getId()).with(user(alice)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/sessions/{id}", UUID.randomUUID()).with(user(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsOnlyTheCallersSessionsNewestFirstWithoutTranscripts() throws Exception {
        mockMvc.perform(get("/sessions").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", contains(aliceNewer.getId().toString(), aliceOlder.getId().toString())))
                .andExpect(jsonPath("$[0]", not(hasKey("transcript"))))
                .andExpect(jsonPath("$[0]", not(hasKey("feedback"))));

        mockMvc.perform(get("/sessions").with(user(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(bobSession.getId().toString())));

        mockMvc.perform(get("/sessions").with(user("nobody-" + UUID.randomUUID() + "@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        mockMvc.perform(get("/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/sessions/{id}", aliceNewer.getId())).andExpect(status().isUnauthorized());
    }
}

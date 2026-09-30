package com.callback.session.service;

import com.callback.session.DTO.GeneratedFeedback;
import com.callback.session.DTO.QuestionFeedbackItem;
import com.callback.session.DTO.TranscriptTurnDto;
import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.InterviewSession;
import com.callback.session.model.QuestionFeedback;
import com.callback.session.model.SessionStatus;
import com.callback.session.repository.FeedbackSummaryRepository;
import com.callback.session.repository.InterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves feedback generation on its own, before the Kafka listener feeds it: a real Groq call
 * (openai/gpt-oss-120b, so GROQ_API_KEY must be exported) and real Postgres persistence. The
 * transcript is handed in directly, exactly as the listener will pass it.
 *
 * <p>LLM output varies run to run, so assertions are structural or relative (a strong answer
 * must out-score a weak one on technical depth) rather than pinned to exact scores or wording.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FeedbackServiceTest {

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private FeedbackSummaryRepository feedbackRepository;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TransactionTemplate tx;

    private final List<UUID> createdSessions = new ArrayList<>();

    private static final String Q1 = "How would you guarantee exactly-once processing of payment events consumed from Kafka?";
    private static final String Q2 = "Tell me about a time you disagreed with a teammate on a design decision.";

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> feedbackRepository.findBySessionId(id).ifPresent(feedbackRepository::delete));
        sessionRepository.deleteAllById(createdSessions);
    }

    @Test
    void generatesGroundedPerQuestionFeedbackAndStrongAnswersOutscoreWeakOnes() {
        InterviewSession strong = newSession();
        feedbackService.generateAndPersist(strong, transcript(
                "I'd make the consumer idempotent: each event carries a payment id, and I write it with a unique "
                        + "constraint in the same Postgres transaction as the balance update, so a redelivery hits the "
                        + "constraint and is skipped. Offsets are committed only after that transaction commits. For "
                        + "Kafka-to-Kafka flows I'd use transactional producers with read_committed consumers instead.",
                "Situation: my lead wanted a shared DB between two services. Task: I owned the billing service. "
                        + "Action: I wrote a one-page comparison with coupling risks and a migration cost estimate and we "
                        + "reviewed it together. Result: we went with an event-based integration and cut cross-team "
                        + "incidents that quarter."));

        InterviewSession weak = newSession();
        feedbackService.generateAndPersist(weak, transcript(
                "Um, I think Kafka does that automatically? You just turn it on.",
                "I don't really disagree with people, I just do what they say."));

        FeedbackSummary strongFb = load(strong);
        FeedbackSummary weakFb = load(weak);

        for (FeedbackSummary fb : List.of(strongFb, weakFb)) {
            assertThat(fb.getOverallSummary()).isNotBlank();
            assertThat(List.of(fb.getClarityScore(), fb.getStructureScore(), fb.getTechnicalDepthScore()))
                    .allSatisfy(score -> assertThat(score).isBetween(1, 10));
            // One item per question actually asked, in the order they were asked.
            assertThat(fb.getPerQuestion()).hasSize(2);
            assertThat(fb.getPerQuestion()).extracting(QuestionFeedback::getOrderIndex).containsExactly(0, 1);
            assertThat(fb.getPerQuestion().get(0).getQuestionText()).containsIgnoringCase("kafka");
            assertThat(fb.getPerQuestion()).allSatisfy(q -> assertThat(q.getFeedbackText()).isNotBlank());
        }
        assertThat(strongFb.getTechnicalDepthScore()).isGreaterThan(weakFb.getTechnicalDepthScore());
        assertThat(strongFb.getStructureScore()).isGreaterThan(weakFb.getStructureScore());
    }

    @Test
    void doesNotRegenerateFeedbackForASessionThatAlreadyHasIt() {
        InterviewSession session = newSession();
        List<TranscriptTurnDto> transcript = transcript("Idempotent consumer keyed on payment id.", "We compromised.");
        feedbackService.generateAndPersist(session, transcript);
        FeedbackSummary first = load(session);

        feedbackService.generateAndPersist(session, transcript);

        FeedbackSummary after = load(session);
        assertThat(after.getId()).isEqualTo(first.getId());
        assertThat(after.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(after.getOverallSummary()).isEqualTo(first.getOverallSummary());
    }

    @Test
    void generatesNothingWhenTheCandidateNeverAnswered() {
        InterviewSession session = newSession();
        feedbackService.generateAndPersist(session, List.of(
                new TranscriptTurnDto("COACH", Q1, "JD lists Kafka.", 0)));

        assertThat(feedbackRepository.existsBySessionId(session.getId())).isFalse();
    }

    @Test
    void validateRejectsMalformedModelOutput() {
        List<QuestionFeedbackItem> ok = List.of(new QuestionFeedbackItem("Q", "F"));
        FeedbackService.validate(new GeneratedFeedback("fine", 5, 5, 5, ok));

        assertThatThrownBy(() -> FeedbackService.validate(new GeneratedFeedback("fine", 0, 5, 5, ok)))
                .hasMessageContaining("clarityScore");
        assertThatThrownBy(() -> FeedbackService.validate(new GeneratedFeedback("fine", 5, 11, 5, ok)))
                .hasMessageContaining("structureScore");
        assertThatThrownBy(() -> FeedbackService.validate(new GeneratedFeedback(" ", 5, 5, 5, ok)))
                .hasMessageContaining("overallSummary");
        assertThatThrownBy(() -> FeedbackService.validate(new GeneratedFeedback("fine", 5, 5, 5, List.of())))
                .hasMessageContaining("perQuestion");
        assertThatThrownBy(() -> FeedbackService.validate(new GeneratedFeedback("fine", 5, 5, 5,
                List.of(new QuestionFeedbackItem("Q", "")))))
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> FeedbackService.validate(null)).hasMessageContaining("no feedback");
    }

    /** Two COACH questions, each followed by the given CANDIDATE answer — deliberately listed out of turnIndex order. */
    private static List<TranscriptTurnDto> transcript(String answer1, String answer2) {
        return List.of(
                new TranscriptTurnDto("CANDIDATE", answer2, null, 3),
                new TranscriptTurnDto("COACH", Q1, "JD lists Kafka event-driven payments.", 0),
                new TranscriptTurnDto("COACH", Q2, "Behavioural; probes collaboration.", 2),
                new TranscriptTurnDto("CANDIDATE", answer1, null, 1));
    }

    private InterviewSession newSession() {
        InterviewSession s = sessionRepository.save(new InterviewSession(UUID.randomUUID(),
                "fb-" + UUID.randomUUID() + "@example.com", UUID.randomUUID(), SessionStatus.COMPLETED,
                Instant.now().minusSeconds(900), Instant.now()));
        createdSessions.add(s.getId());
        return s;
    }

    /** Loads inside a transaction so the lazy perQuestion list is initialised before it's asserted on. */
    private FeedbackSummary load(InterviewSession session) {
        return tx.execute(status -> {
            FeedbackSummary fb = feedbackRepository.findBySessionId(session.getId()).orElseThrow();
            fb.getPerQuestion().size();
            return fb;
        });
    }
}

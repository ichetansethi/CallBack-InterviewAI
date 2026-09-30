package com.callback.session.repository;

import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.InterviewSession;
import com.callback.session.model.QuestionFeedback;
import com.callback.session.model.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the feedback persistence shape on its own — no LLM, no Kafka — against real Postgres
 * (callback_session DB). Each test runs in a transaction that @DataJpaTest rolls back, so nothing
 * is left behind; flush() + clear() force the real INSERTs and a genuine reload from the DB.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FeedbackSummaryRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private FeedbackSummaryRepository feedbackRepository;

    private InterviewSession session;

    @BeforeEach
    void seedSession() {
        session = em.persist(new InterviewSession(UUID.randomUUID(), "fb-" + UUID.randomUUID() + "@example.com",
                UUID.randomUUID(), SessionStatus.COMPLETED, Instant.now().minusSeconds(600), Instant.now()));
    }

    @Test
    void cascadesPerQuestionFeedbackAndReloadsItInOrderIndexOrder() {
        FeedbackSummary summary = new FeedbackSummary(session, "Solid answers, light on metrics.", 7, 5, 8);
        // Added deliberately out of orderIndex order: @OrderBy, not insertion order, must decide the reload order.
        summary.setPerQuestion(List.of(
                new QuestionFeedback(summary, "Q3", "third", 2),
                new QuestionFeedback(summary, "Q1", "first", 0),
                new QuestionFeedback(summary, "Q2", "second", 1)));
        feedbackRepository.save(summary);
        em.flush();
        em.clear();

        FeedbackSummary reloaded = feedbackRepository.findBySessionId(session.getId()).orElseThrow();
        assertThat(reloaded.getOverallSummary()).isEqualTo("Solid answers, light on metrics.");
        assertThat(reloaded.getClarityScore()).isEqualTo(7);
        assertThat(reloaded.getStructureScore()).isEqualTo(5);
        assertThat(reloaded.getTechnicalDepthScore()).isEqualTo(8);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getPerQuestion()).extracting(QuestionFeedback::getQuestionText).containsExactly("Q1", "Q2", "Q3");
        assertThat(reloaded.getPerQuestion()).extracting(QuestionFeedback::getOrderIndex).containsExactly(0, 1, 2);
        assertThat(feedbackRepository.existsBySessionId(session.getId())).isTrue();
    }

    @Test
    void rejectsASecondSummaryForTheSameSession() {
        feedbackRepository.save(new FeedbackSummary(session, "first", 5, 5, 5));
        em.flush();

        feedbackRepository.save(new FeedbackSummary(session, "duplicate", 6, 6, 6));
        assertThatThrownBy(em::flush).hasMessageContaining("unique");
    }

    @Test
    void reportsNoSummaryForASessionWithoutFeedback() {
        assertThat(feedbackRepository.findBySessionId(session.getId())).isEmpty();
        assertThat(feedbackRepository.existsBySessionId(session.getId())).isFalse();
    }
}

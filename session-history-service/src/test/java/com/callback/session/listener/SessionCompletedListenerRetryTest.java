package com.callback.session.listener;

import com.callback.session.repository.InterviewSessionRepository;
import com.callback.session.repository.TranscriptTurnRepository;
import com.callback.session.service.FeedbackService;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Proves the retry path the real-Groq listener test can't trigger on demand: a transient
 * feedback failure (standing in for a Groq 429) is retried with backoff, and because
 * SessionIngestService is idempotent the retries never duplicate the transcript. FeedbackService
 * is the only thing faked — its own behaviour is proven in FeedbackServiceTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionCompletedListenerRetryTest {

    private static final String RUN = UUID.randomUUID().toString();
    private static final String TOPIC = "session-completed-retry-it-" + RUN;

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("session-history.kafka.topic", () -> TOPIC);
        registry.add("session-history.kafka.group-id", () -> "session-history-retry-it-" + RUN);
    }

    @MockitoBean
    private FeedbackService feedbackService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TranscriptTurnRepository turnRepository;

    private static KafkaAdmin kafkaAdmin;

    @Autowired
    void captureAdmin(KafkaAdmin admin) {
        kafkaAdmin = admin;
    }

    private final UUID sessionId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        turnRepository.deleteAll(turnRepository.findBySessionIdOrderByTurnIndexAsc(sessionId));
        sessionRepository.deleteById(sessionId);
    }

    @AfterAll
    static void deleteTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.deleteTopics(List.of(TOPIC)).all().get();
        }
    }

    @Test
    void transientFeedbackFailureIsRetriedWithoutDuplicatingTheTranscript() {
        doThrow(new RuntimeException("HTTP 429 - rate_limit_exceeded"))
                .doThrow(new RuntimeException("HTTP 429 - rate_limit_exceeded"))
                .doNothing()
                .when(feedbackService).generateAndPersist(any(), any());

        long start = System.nanoTime();
        kafkaTemplate.send(TOPIC, sessionId.toString(), """
                {"sessionId":"%s","ownerEmail":"retry-%s@example.com","questionSetId":"%s",
                 "startedAt":"2026-09-30T09:00:00Z","endedAt":"2026-09-30T09:05:00Z",
                 "transcript":[
                  {"speaker":"COACH","text":"Q?","questionRationale":"r","turnIndex":0},
                  {"speaker":"CANDIDATE","text":"A.","questionRationale":null,"turnIndex":1}]}"""
                .formatted(sessionId, RUN, UUID.randomUUID())).join();

        // 1 initial attempt + 2 retries; the third call succeeds.
        verify(feedbackService, timeout(Duration.ofSeconds(30).toMillis()).times(3)).generateAndPersist(any(), any());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(6)); // 2s + 4s backoff actually applied
        assertThat(sessionRepository.findById(sessionId)).isPresent();
        assertThat(turnRepository.findBySessionIdOrderByTurnIndexAsc(sessionId)).hasSize(2);
    }
}

package com.callback.session.listener;

import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.Speaker;
import com.callback.session.model.TranscriptTurn;
import com.callback.session.repository.FeedbackSummaryRepository;
import com.callback.session.repository.InterviewSessionRepository;
import com.callback.session.repository.TranscriptTurnRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the listener stage end to end against the real local broker (docker-compose's
 * callback-kafka on localhost:9092), real Postgres and — for the one event with candidate
 * answers — real Groq. Events are sent as raw JSON strings, exactly as a producer in another
 * service would, rather than serialized from this service's own DTO class.
 *
 * <p>Each run gets its own topic and consumer group so it can't consume, or be starved by, a
 * dev instance of this service running at the same time. The topic has one partition, so events
 * are processed in send order: every negative case sends a known-good "marker" event after it,
 * and waiting for the marker proves the bad event was already handled and skipped rather than
 * blocking the topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionCompletedListenerTest {

    private static final String RUN = UUID.randomUUID().toString();
    private static final String TOPIC = "session-completed-it-" + RUN;

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("session-history.kafka.topic", () -> TOPIC);
        registry.add("session-history.kafka.group-id", () -> "session-history-it-" + RUN);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TranscriptTurnRepository turnRepository;

    @Autowired
    private FeedbackSummaryRepository feedbackRepository;

    private static KafkaAdmin kafkaAdmin;

    @Autowired
    void captureAdmin(KafkaAdmin admin) {
        kafkaAdmin = admin;
    }

    private final List<UUID> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : createdSessions) {
            feedbackRepository.findBySessionId(id).ifPresent(feedbackRepository::delete);
            turnRepository.deleteAll(turnRepository.findBySessionIdOrderByTurnIndexAsc(id));
        }
        sessionRepository.deleteAllById(createdSessions);
    }

    @AfterAll
    static void deleteTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.deleteTopics(List.of(TOPIC)).all().get();
        }
    }

    @Test
    void recordsSessionWithOrderedTranscriptAndFeedbackIgnoringForeignTypeHeaders() {
        UUID sessionId = track(UUID.randomUUID());
        UUID questionSetId = UUID.randomUUID();
        String owner = "listener-" + RUN + "@example.com";
        String json = """
                {"sessionId":"%s","ownerEmail":"%s","questionSetId":"%s",
                 "startedAt":"2026-09-30T09:00:00Z","endedAt":"2026-09-30T09:10:00Z",
                 "transcript":[
                  {"speaker":"CANDIDATE","text":"I'd dedupe on the payment id with a unique constraint in the same DB transaction as the write, and commit offsets only after it commits.","questionRationale":null,"turnIndex":1},
                  {"speaker":"COACH","text":"How would you avoid double-charging when a Kafka consumer redelivers a payment event?","questionRationale":"JD lists Kafka event-driven payments.","turnIndex":0}
                 ]}""".formatted(sessionId, owner, questionSetId);

        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, sessionId.toString(), json);
        // What a Spring JsonSerializer in voice-orchestrator would attach: a class this service doesn't have.
        record.headers().add("__TypeId__", "com.callback.voice.event.SessionCompletedEvent".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();

        awaitTrue(() -> feedbackRepository.existsBySessionId(sessionId), Duration.ofSeconds(90));

        var session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getOwnerEmail()).isEqualTo(owner);
        assertThat(session.getQuestionSetId()).isEqualTo(questionSetId);
        assertThat(session.getStartedAt()).isEqualTo(Instant.parse("2026-09-30T09:00:00Z"));
        assertThat(session.getEndedAt()).isEqualTo(Instant.parse("2026-09-30T09:10:00Z"));

        List<TranscriptTurn> turns = turnRepository.findBySessionIdOrderByTurnIndexAsc(sessionId);
        assertThat(turns).extracting(TranscriptTurn::getSpeaker).containsExactly(Speaker.COACH, Speaker.CANDIDATE);
        assertThat(turns.get(0).getQuestionRationale()).isEqualTo("JD lists Kafka event-driven payments.");

        FeedbackSummary feedback = feedbackRepository.findBySessionId(sessionId).orElseThrow();
        assertThat(feedback.getTechnicalDepthScore()).isBetween(1, 10);
        assertThat(feedback.getOverallSummary()).isNotBlank();
    }

    @Test
    void redeliveredEventIsNotDuplicated() {
        UUID sessionId = track(UUID.randomUUID());
        String json = coachOnlyEvent(sessionId, "COACH");
        send(sessionId, json);
        send(sessionId, json);
        UUID marker = sendMarker();

        awaitTrue(() -> sessionRepository.existsById(marker), Duration.ofSeconds(30));

        assertThat(sessionRepository.findById(sessionId)).isPresent();
        assertThat(turnRepository.findBySessionIdOrderByTurnIndexAsc(sessionId)).hasSize(1);
    }

    @Test
    void malformedJsonIsSkippedWithoutBlockingLaterEvents() {
        kafkaTemplate.send(TOPIC, "garbage", "{this is not json").join();
        UUID marker = sendMarker();

        awaitTrue(() -> sessionRepository.existsById(marker), Duration.ofSeconds(30));
    }

    @Test
    void unknownSpeakerIsRejectedWithoutLeavingAPartialSession() {
        UUID badSession = track(UUID.randomUUID());
        send(badSession, coachOnlyEvent(badSession, "INTERVIEWER"));
        UUID marker = sendMarker();

        awaitTrue(() -> sessionRepository.existsById(marker), Duration.ofSeconds(30));

        assertThat(sessionRepository.findById(badSession)).isEmpty();
        assertThat(turnRepository.findBySessionIdOrderByTurnIndexAsc(badSession)).isEmpty();
    }

    /** One COACH turn and no candidate answer: fully recorded, but no model call is made for it. */
    private String coachOnlyEvent(UUID sessionId, String speaker) {
        return """
                {"sessionId":"%s","ownerEmail":"listener-%s@example.com","questionSetId":"%s",
                 "startedAt":"2026-09-30T09:00:00Z","endedAt":"2026-09-30T09:01:00Z",
                 "transcript":[{"speaker":"%s","text":"Tell me about yourself.","questionRationale":"opener","turnIndex":0}]}"""
                .formatted(sessionId, RUN, UUID.randomUUID(), speaker);
    }

    private UUID sendMarker() {
        UUID marker = track(UUID.randomUUID());
        send(marker, coachOnlyEvent(marker, "COACH"));
        return marker;
    }

    private void send(UUID key, String json) {
        kafkaTemplate.send(TOPIC, key.toString(), json).join();
    }

    private UUID track(UUID sessionId) {
        createdSessions.add(sessionId);
        return sessionId;
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}

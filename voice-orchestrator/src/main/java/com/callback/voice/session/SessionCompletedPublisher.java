package com.callback.voice.session;

import com.callback.voice.DTO.SessionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Hands a finished interview to session-history-service via the session-completed topic, then
 * clears it from Redis.
 *
 * <p>Each completed interview gets a fresh UUID. The Redis identity (ownerEmail::questionSetId) is
 * reused whenever the same candidate practises the same question set again, so deriving the id
 * from it would make session-history-service's idempotency check silently drop every later run.
 *
 * <p>That UUID is minted once per run and saved into the Redis state before the first send. A
 * re-publish of the same run (after a failed Redis delete, an ambiguous send timeout, or a
 * reconnect to an ended session — see VoiceWebSocketHandler) reuses it, so the duplicate lands on
 * the same key and session-history-service drops it. A fresh UUID per attempt would instead look
 * like a second interview.
 *
 * <p>The Redis session is deleted only after the broker acknowledges the event, so the next
 * connection starts a fresh interview instead of appending to a finished one. If publishing fails
 * the session is left in Redis (transcript and history id kept until the TTL) and the error
 * propagates for the caller to log.
 */
@Component
public class SessionCompletedPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletedPublisher.class);

    private final KafkaTemplate<String, SessionCompletedEvent> kafkaTemplate;
    private final InterviewSessionRepository sessionRepository;
    private final String topic;

    public SessionCompletedPublisher(KafkaTemplate<String, SessionCompletedEvent> kafkaTemplate,
                                     InterviewSessionRepository sessionRepository,
                                     @Value("${session-history.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.sessionRepository = sessionRepository;
        this.topic = topic;
    }

    /** Emits the published event's sessionId once the broker has acknowledged it and Redis is cleared. */
    public Mono<UUID> publishCompleted(InterviewSessionState state, UUID questionSetId) {
        InterviewSessionState completed = state.completedSessionId() != null
                ? state
                : state.withCompletedSessionId(UUID.randomUUID());
        SessionCompletedEvent event = SessionCompletedEventMapper.toEvent(completed, completed.completedSessionId(), questionSetId);
        String key = event.sessionId().toString();

        // Persist the id before sending: if the send's outcome is lost, the retry must reuse it.
        return sessionRepository.save(completed)
                // send() itself can block (metadata fetch) — keep it off the Netty event loop.
                .then(Mono.defer(() -> Mono.fromFuture(kafkaTemplate.send(topic, key, event)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnNext(result -> log.info("Published session-completed {} for {} ({} turns) to {}-{}@{}",
                        key, state.sessionId(), event.transcript().size(), result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset()))
                .then(sessionRepository.delete(state.sessionId()))
                .thenReturn(event.sessionId());
    }
}

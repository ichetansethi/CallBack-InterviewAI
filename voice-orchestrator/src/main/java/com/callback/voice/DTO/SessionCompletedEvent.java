package com.callback.voice.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the session-completed Kafka topic, consumed by session-history-service
 * (its com.callback.session.DTO.SessionCompletedEvent). Deliberately a local copy, not a shared
 * dependency — same as this service's other DTOs; field names and types must stay in step with
 * the consumer's by hand.
 */
public record SessionCompletedEvent(
        UUID sessionId, String ownerEmail, UUID questionSetId,
        List<TranscriptTurnDto> transcript, Instant startedAt, Instant endedAt
) {}

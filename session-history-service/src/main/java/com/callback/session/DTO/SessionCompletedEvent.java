package com.callback.session.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionCompletedEvent(
        UUID sessionId, String ownerEmail, UUID questionSetId,
        List<TranscriptTurnDto> transcript, Instant startedAt, Instant endedAt
) {}

package com.callback.session.DTO;

import com.callback.session.model.SessionStatus;

import java.time.Instant;
import java.util.UUID;

/** List-view shape: metadata only. The transcript is fetched per session via GET /sessions/{id}. */
public record SessionSummaryResponse(UUID id, UUID questionSetId, SessionStatus status, Instant startedAt, Instant endedAt) {}

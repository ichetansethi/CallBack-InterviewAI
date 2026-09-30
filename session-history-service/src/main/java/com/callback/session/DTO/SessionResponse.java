package com.callback.session.DTO;

import com.callback.session.model.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** feedback is null until FeedbackService has run for this session, or when there was nothing to evaluate. */
public record SessionResponse(UUID id, UUID questionSetId, SessionStatus status, Instant startedAt, Instant endedAt,
                              List<TranscriptTurnResponse> transcript, FeedbackResponse feedback) {}

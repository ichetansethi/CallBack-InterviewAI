package com.callback.compatibility.DTO;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRequestedEvent(
        UUID analysisId, String ownerEmail, UUID jdId, UUID resumeId,
        String jdText, String resumeText, Instant requestedAt) {}

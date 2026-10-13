package com.callback.compatibility.DTO;

import com.callback.compatibility.model.AnalysisStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scores and suggestions are null until status is COMPLETED; failureReason is set only when FAILED. */
public record AnalyzeResponse(
        UUID id, UUID jdId, UUID resumeId,
        AnalysisStatus status, String failureReason,
        Integer skillsOverlap, Integer experienceMatch, Integer keywordCoverage, Integer semanticSimilarity,
        List<SuggestionResponse> suggestions,
        Instant createdAt, Instant completedAt
) {}

package com.callback.compatibility.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalyzeResponse(
        UUID id, UUID jdId, UUID resumeId,
        int skillsOverlap, int experienceMatch, int keywordCoverage, int semanticSimilarity,
        List<SuggestionResponse> suggestions,
        Instant createdAt
) {}

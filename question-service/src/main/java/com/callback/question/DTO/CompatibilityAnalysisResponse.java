package com.callback.question.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompatibilityAnalysisResponse(
        UUID id, UUID jdId, UUID resumeId,
        int skillsOverlap, int experienceMatch, int keywordCoverage, int semanticSimilarity,
        List<SuggestionResponse> suggestions,
        Instant createdAt
) {
    public record SuggestionResponse(String jdRequirement, String suggestion) {}

    /** The JD requirements this analysis found unsupported by the resume — not the resume-tailoring
     * advice text on each suggestion, which is a separate concern. */
    public List<String> uncoveredRequirements() {
        return suggestions().stream().map(SuggestionResponse::jdRequirement).toList();
    }
}

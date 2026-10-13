package com.callback.question.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * compatibility-service's GET /compatibility/{id}. Analyses run asynchronously there, so scores and
 * suggestions are null until status is COMPLETED. The scores are Integer, not int: Jackson would
 * otherwise read those nulls as 0 without complaint.
 */
public record CompatibilityAnalysisResponse(
        UUID id, UUID jdId, UUID resumeId,
        String status, String failureReason,
        Integer skillsOverlap, Integer experienceMatch, Integer keywordCoverage, Integer semanticSimilarity,
        List<SuggestionResponse> suggestions,
        Instant createdAt, Instant completedAt
) {
    public record SuggestionResponse(String jdRequirement, String suggestion) {}

    /** No status at all means a compatibility-service from before async analysis, which only ever
     * returned finished analyses. */
    public boolean isCompleted() {
        return status == null || "COMPLETED".equals(status);
    }

    /** The JD requirements this analysis found unsupported by the resume — not the resume-tailoring
     * advice text on each suggestion, which is a separate concern. */
    public List<String> uncoveredRequirements() {
        return suggestions().stream().map(SuggestionResponse::jdRequirement).toList();
    }
}

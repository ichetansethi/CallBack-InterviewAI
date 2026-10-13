package com.callback.compatibility.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "compatibility_analyses")
public class CompatibilityAnalysis {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String ownerEmail;

    @Column(nullable = false)
    private UUID jdId;

    @Column(nullable = false)
    private UUID resumeId;

    private Integer skillsOverlap;

    private Integer experienceMatch;

    private Integer keywordCoverage;

    private Integer semanticSimilarity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnalysisSuggestion> suggestions = new ArrayList<>();

    protected CompatibilityAnalysis() {}

    public CompatibilityAnalysis(String ownerEmail, UUID jdId, UUID resumeId,
                                  int skillsOverlap, int experienceMatch,
                                  int keywordCoverage, int semanticSimilarity) {
        this.ownerEmail = ownerEmail;
        this.jdId = jdId;
        this.resumeId = resumeId;
        this.skillsOverlap = skillsOverlap;
        this.experienceMatch = experienceMatch;
        this.keywordCoverage = keywordCoverage;
        this.semanticSimilarity = semanticSimilarity;
        this.status = AnalysisStatus.COMPLETED;
        this.createdAt = Instant.now();
        this.completedAt = this.createdAt;
    }

    private CompatibilityAnalysis(String ownerEmail, UUID jdId, UUID resumeId) {
        this.ownerEmail = ownerEmail;
        this.jdId = jdId;
        this.resumeId = resumeId;
        this.status = AnalysisStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** A queued analysis: no scores or suggestions until {@link #complete} runs. */
    public static CompatibilityAnalysis pending(String ownerEmail, UUID jdId, UUID resumeId) {
        return new CompatibilityAnalysis(ownerEmail, jdId, resumeId);
    }

    public void markInProgress() {
        this.status = AnalysisStatus.IN_PROGRESS;
    }

    /**
     * Writes the whole result at once. Clears any existing suggestions first, so completing an
     * analysis that is being re-run never leaves duplicates behind.
     */
    public void complete(int skillsOverlap, int experienceMatch, int keywordCoverage,
                         int semanticSimilarity, List<AnalysisSuggestion> newSuggestions) {
        this.skillsOverlap = skillsOverlap;
        this.experienceMatch = experienceMatch;
        this.keywordCoverage = keywordCoverage;
        this.semanticSimilarity = semanticSimilarity;
        suggestions.clear();
        newSuggestions.forEach(this::addSuggestion);
        this.status = AnalysisStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    /** {@code reason} is shown to the caller, so keep it short and free of internal detail. */
    public void markFailed(String reason) {
        this.status = AnalysisStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public void addSuggestion(AnalysisSuggestion suggestion) {
        suggestion.setAnalysis(this);
        suggestions.add(suggestion);
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public UUID getJdId() {
        return jdId;
    }

    public UUID getResumeId() {
        return resumeId;
    }

    public Integer getSkillsOverlap() {
        return skillsOverlap;
    }

    public Integer getExperienceMatch() {
        return experienceMatch;
    }

    public Integer getKeywordCoverage() {
        return keywordCoverage;
    }

    public Integer getSemanticSimilarity() {
        return semanticSimilarity;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<AnalysisSuggestion> getSuggestions() {
        return suggestions;
    }
}

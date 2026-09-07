package com.callback.compatibility.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(nullable = false)
    private int skillsOverlap;

    @Column(nullable = false)
    private int experienceMatch;

    @Column(nullable = false)
    private int keywordCoverage;

    @Column(nullable = false)
    private int semanticSimilarity;

    @Column(nullable = false)
    private Instant createdAt;

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
        this.createdAt = Instant.now();
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

    public int getSkillsOverlap() {
        return skillsOverlap;
    }

    public int getExperienceMatch() {
        return experienceMatch;
    }

    public int getKeywordCoverage() {
        return keywordCoverage;
    }

    public int getSemanticSimilarity() {
        return semanticSimilarity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<AnalysisSuggestion> getSuggestions() {
        return suggestions;
    }
}

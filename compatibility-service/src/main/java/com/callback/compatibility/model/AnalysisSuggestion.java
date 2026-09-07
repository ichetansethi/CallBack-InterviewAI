package com.callback.compatibility.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "analysis_suggestions")
public class AnalysisSuggestion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "analysis_id", nullable = false)
    private CompatibilityAnalysis analysis;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String jdRequirement;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String suggestion;

    protected AnalysisSuggestion() {}

    public AnalysisSuggestion(String jdRequirement, String suggestion) {
        this.jdRequirement = jdRequirement;
        this.suggestion = suggestion;
    }

    void setAnalysis(CompatibilityAnalysis analysis) {
        this.analysis = analysis;
    }

    public UUID getId() {
        return id;
    }

    public String getJdRequirement() {
        return jdRequirement;
    }

    public String getSuggestion() {
        return suggestion;
    }
}

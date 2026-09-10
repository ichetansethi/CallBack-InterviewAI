package com.callback.question.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

@Entity
public class QuestionSet {
    @Id @GeneratedValue private UUID id;
    private String ownerEmail;
    private UUID jdId;
    private UUID compatibilityAnalysisId; // nullable — tailoring is optional
    private Instant createdAt;

    protected QuestionSet() {
    }

    public QuestionSet(String ownerEmail, UUID jdId, UUID compatibilityAnalysisId) {
        this.ownerEmail = ownerEmail;
        this.jdId = jdId;
        this.compatibilityAnalysisId = compatibilityAnalysisId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public UUID getJdId() {
        return jdId;
    }

    public void setJdId(UUID jdId) {
        this.jdId = jdId;
    }

    public UUID getCompatibilityAnalysisId() {
        return compatibilityAnalysisId;
    }

    public void setCompatibilityAnalysisId(UUID compatibilityAnalysisId) {
        this.compatibilityAnalysisId = compatibilityAnalysisId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

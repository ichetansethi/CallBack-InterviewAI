package com.callback.session.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

@Entity
public class InterviewSession {
    @Id
    private UUID id;
    private String ownerEmail;
    private UUID questionSetId;
    @Enumerated(EnumType.STRING) private SessionStatus status;
    private Instant startedAt;
    private Instant endedAt;
    protected InterviewSession() {}

    public InterviewSession(UUID id, String ownerEmail, UUID questionSetId, SessionStatus status,
                            Instant startedAt, Instant endedAt) {
        this.id = id;
        this.ownerEmail = ownerEmail;
        this.questionSetId = questionSetId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
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

    public UUID getQuestionSetId() {
        return questionSetId;
    }

    public void setQuestionSetId(UUID questionSetId) {
        this.questionSetId = questionSetId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}

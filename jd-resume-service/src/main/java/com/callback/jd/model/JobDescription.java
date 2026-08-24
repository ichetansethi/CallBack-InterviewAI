package com.callback.jd.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_descriptions")
public class JobDescription {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String ownerEmail;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawText;

    @Column(nullable = false)
    private String role;

    private String company; // nullable — not every JD names the company

    @Column(nullable = false)
    private Instant createdAt;

    protected JobDescription() {}

    public JobDescription(String ownerEmail, String rawText, String role, String company) {
        this.ownerEmail = ownerEmail;
        this.rawText = rawText;
        this.role = role;
        this.company = company;
        this.createdAt = Instant.now();
    }

    public String getRawText() {
        return rawText;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public String getRole() {
        return role;
    }

    public String getCompany() {
        return company;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
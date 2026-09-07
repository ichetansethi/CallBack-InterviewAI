package com.callback.jd.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resumes")
public class Resume {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String ownerEmail;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String storageKey; // reference into storage — not the file itself

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long fileSizeBytes;

    @Column(nullable = false)
    private Instant uploadedAt;

    @Column(columnDefinition = "TEXT")
    private String extractedText; // best-effort text extraction; null if unsupported type or extraction failed

    protected Resume() {}

    public Resume(String ownerEmail, String originalFilename, String storageKey, String contentType, long fileSizeBytes) {
        this.ownerEmail = ownerEmail;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.fileSizeBytes = fileSizeBytes;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }
}

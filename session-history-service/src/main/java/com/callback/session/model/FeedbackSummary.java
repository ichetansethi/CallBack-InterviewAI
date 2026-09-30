package com.callback.session.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
public class FeedbackSummary {
    @Id @GeneratedValue private UUID id;
    @OneToOne(optional = false)
    @JoinColumn(unique = true) // at most one summary per session — backs FeedbackService's idempotency
    private InterviewSession session;
    @Column(columnDefinition = "TEXT") private String overallSummary;
    private int clarityScore;         // 1-10
    private int structureScore;       // 1-10, STAR-method adherence
    private int technicalDepthScore;  // 1-10
    private Instant createdAt;
    @OneToMany(mappedBy = "summary", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<QuestionFeedback> perQuestion = new ArrayList<>();

    protected FeedbackSummary() {
    }

    public FeedbackSummary(InterviewSession session, String overallSummary, int clarityScore,
                           int structureScore, int technicalDepthScore) {
        this.session = session;
        this.overallSummary = overallSummary;
        this.clarityScore = clarityScore;
        this.structureScore = structureScore;
        this.technicalDepthScore = technicalDepthScore;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public InterviewSession getSession() {
        return session;
    }

    public String getOverallSummary() {
        return overallSummary;
    }

    public int getClarityScore() {
        return clarityScore;
    }

    public int getStructureScore() {
        return structureScore;
    }

    public int getTechnicalDepthScore() {
        return technicalDepthScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<QuestionFeedback> getPerQuestion() {
        return perQuestion;
    }

    public void setPerQuestion(List<QuestionFeedback> perQuestion) {
        this.perQuestion.clear();
        this.perQuestion.addAll(perQuestion);
    }
}

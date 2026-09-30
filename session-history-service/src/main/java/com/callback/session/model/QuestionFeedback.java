package com.callback.session.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
public class QuestionFeedback {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false)
    private FeedbackSummary summary;
    @Column(columnDefinition = "TEXT") private String questionText;
    @Column(columnDefinition = "TEXT") private String feedbackText;
    private int orderIndex;

    protected QuestionFeedback() {
    }

    public QuestionFeedback(FeedbackSummary summary, String questionText, String feedbackText, int orderIndex) {
        this.summary = summary;
        this.questionText = questionText;
        this.feedbackText = feedbackText;
        this.orderIndex = orderIndex;
    }

    public UUID getId() {
        return id;
    }

    public FeedbackSummary getSummary() {
        return summary;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getFeedbackText() {
        return feedbackText;
    }

    public int getOrderIndex() {
        return orderIndex;
    }
}

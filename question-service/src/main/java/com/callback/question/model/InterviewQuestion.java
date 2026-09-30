package com.callback.question.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
public class InterviewQuestion {
    @Id @GeneratedValue private UUID id;
    @ManyToOne
    private QuestionSet questionSet;
    private String category;      // "technical" | "behavioral" | "role-specific"
    @Column(columnDefinition = "TEXT") private String questionText;
    @Column(columnDefinition = "TEXT") private String rationale; // why this question, grounded in JD/gap
    private int orderIndex; // position within the set, 0-based — voice-orchestrator asks questions in this order

    protected InterviewQuestion() {
    }

    /** orderIndex is the question's position in its set — reads sort by it, so it must be set. */
    public InterviewQuestion(QuestionSet questionSet, String category, String questionText, String rationale, int orderIndex) {
        this.questionSet = questionSet;
        this.category = category;
        this.questionText = questionText;
        this.rationale = rationale;
        this.orderIndex = orderIndex;
    }

    public UUID getId() {
        return id;
    }

    public QuestionSet getQuestionSet() {
        return questionSet;
    }

    public String getCategory() {
        return category;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getRationale() {
        return rationale;
    }

    public int getOrderIndex() {
        return orderIndex;
    }
}

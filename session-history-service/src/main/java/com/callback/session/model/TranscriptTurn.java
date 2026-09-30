package com.callback.session.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
public class TranscriptTurn {
    @Id
    @GeneratedValue
    private UUID id;
    @ManyToOne
    private InterviewSession session;
    @Enumerated(EnumType.STRING) private Speaker speaker;
    @Column(columnDefinition = "TEXT") private String text;
    @Column(columnDefinition = "TEXT") private String questionRationale; // nullable — only COACH question turns carry one
    private int turnIndex;

    protected TranscriptTurn() {
    }

    public TranscriptTurn(InterviewSession session, Speaker speaker, String text, String questionRationale, int turnIndex) {
        this.session = session;
        this.speaker = speaker;
        this.text = text;
        this.questionRationale = questionRationale;
        this.turnIndex = turnIndex;
    }

    public UUID getId() {
        return id;
    }

    public InterviewSession getSession() {
        return session;
    }

    public Speaker getSpeaker() {
        return speaker;
    }

    public String getText() {
        return text;
    }

    public String getQuestionRationale() {
        return questionRationale;
    }

    public int getTurnIndex() {
        return turnIndex;
    }
}

package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code questions} is the prepared question list fetched from question-service once, at session
 * creation — never re-fetched or overwritten on a resumed connection, so the interview's question
 * set can't silently change mid-conversation even if question-service's data changes later.
 * {@code currentQuestionIndex} is this service's own progress cursor into that list; question-service
 * has no concept of "current question" itself (it only stores a flat ordered list).
 */
public record InterviewSessionState(String sessionId, String ownerEmail, int turnCount, List<TurnRecord> history,
                                     List<InterviewQuestionDto> questions, int currentQuestionIndex,
                                     Instant createdAt, Instant updatedAt) {

    public static InterviewSessionState newSession(String sessionId, String ownerEmail,
                                                     List<InterviewQuestionDto> questions) {
        Instant now = Instant.now();
        return new InterviewSessionState(sessionId, ownerEmail, 0, List.of(), List.copyOf(questions), 0, now, now);
    }

    /**
     * Advances {@code currentQuestionIndex} when the turn's action was "advance", clamped so it
     * never runs past the last prepared question even if the model says "advance" while already on
     * it (see TurnDecisionService, which tells the model explicitly when it's on the last question
     * so this clamp is a safety net, not the primary mechanism).
     */
    public InterviewSessionState withTurn(TurnRecord turn) {
        List<TurnRecord> updatedHistory = new ArrayList<>(history);
        updatedHistory.add(turn);

        // questions can be null for a state deserialized from a session written before this field
        // existed (still-live Redis data from before this schema change) — guard rather than NPE.
        int updatedIndex = "advance".equalsIgnoreCase(turn.action()) && questions != null && !questions.isEmpty()
                ? Math.min(currentQuestionIndex + 1, questions.size() - 1)
                : currentQuestionIndex;

        return new InterviewSessionState(sessionId, ownerEmail, turnCount + 1, List.copyOf(updatedHistory),
                questions, updatedIndex, createdAt, Instant.now());
    }

}

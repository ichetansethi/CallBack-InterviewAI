package com.callback.voice.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record InterviewSessionState(String sessionId, String ownerEmail, int turnCount, List<TurnRecord> history,
                                     Instant createdAt, Instant updatedAt) {

    public static InterviewSessionState newSession(String sessionId, String ownerEmail) {
        Instant now = Instant.now();
        return new InterviewSessionState(sessionId, ownerEmail, 0, List.of(), now, now);
    }

    public InterviewSessionState withTurn(TurnRecord turn) {
        List<TurnRecord> updatedHistory = new ArrayList<>(history);
        updatedHistory.add(turn);
        return new InterviewSessionState(sessionId, ownerEmail, turnCount + 1, List.copyOf(updatedHistory), createdAt, Instant.now());
    }

}

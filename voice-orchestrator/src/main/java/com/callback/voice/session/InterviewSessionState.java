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
 * has no concept of "current question" itself (it only stores a flat ordered list). It is the index
 * of the prepared question most recently ASKED — the one currently being discussed. Before the
 * opening exchange ({@code turnCount == 0}) it is 0 but nothing has been asked yet; the opening
 * exchange is what asks question 1 (see nextQuestionIndex and TurnDecisionService's opening prompt).
 *
 * <p>{@code followUpCountForCurrentQuestion} counts the follow-ups asked on that question; it resets
 * to 0 whenever the cursor moves. TurnActionPolicy caps it (MAX_FOLLOW_UPS_PER_QUESTION), so a
 * question can't absorb the rest of the interview. Sessions saved to Redis before this field
 * existed deserialize it as 0 (Jackson's default for a missing primitive).
 */
public record InterviewSessionState(String sessionId, String ownerEmail, int turnCount, List<TurnRecord> history,
                                     List<InterviewQuestionDto> questions, int currentQuestionIndex,
                                     int followUpCountForCurrentQuestion,
                                     Instant createdAt, Instant updatedAt) {

    public static InterviewSessionState newSession(String sessionId, String ownerEmail,
                                                     List<InterviewQuestionDto> questions) {
        Instant now = Instant.now();
        return new InterviewSessionState(sessionId, ownerEmail, 0, List.of(), List.copyOf(questions), 0, 0, now, now);
    }

    /**
     * Moves {@code currentQuestionIndex} per nextQuestionIndex and updates the follow-up count. The
     * clamp at the last question is a backstop only: TurnActionPolicy doesn't allow "advance" there,
     * so TurnDecisionService never accepts one.
     */
    public InterviewSessionState withTurn(TurnRecord turn) {
        List<TurnRecord> updatedHistory = new ArrayList<>(history);
        updatedHistory.add(turn);

        int updatedIndex = nextQuestionIndex(turnCount, currentQuestionIndex, turn.action(), questions);
        // The opening exchange asks question 1 (not a follow-up), and a moved cursor is a new question.
        int updatedFollowUps = turnCount == 0 || updatedIndex != currentQuestionIndex
                ? 0
                : followUpCountForCurrentQuestion + ("follow_up".equalsIgnoreCase(turn.action()) ? 1 : 0);

        return new InterviewSessionState(sessionId, ownerEmail, turnCount + 1, List.copyOf(updatedHistory),
                questions, updatedIndex, updatedFollowUps, createdAt, Instant.now());
    }

    /**
     * The single rule for how a turn moves the question cursor ("most recently asked question").
     * Shared with SessionCompletedEventMapper, which replays it over the history to recover which
     * prepared question each interviewer turn was on — so the two can never drift apart.
     *
     * <p>The opening exchange ({@code priorTurns == 0}) asks question 1, so it lands on index 0
     * whatever the model labelled it — before this rule, the model's opener was an "advance" that
     * asked question 1 but moved the cursor to question 2, leaving the cursor one ahead of what was
     * spoken for the rest of the interview (found live: the last prepared question was never asked).
     * After that, "advance" asks the next question, clamped at the last one.
     *
     * <p>questions can be null for a state deserialized from a session written before this field
     * existed (still-live Redis data from before this schema change) — guard rather than NPE.
     */
    static int nextQuestionIndex(int priorTurns, int currentIndex, String action, List<InterviewQuestionDto> questions) {
        if (questions == null || questions.isEmpty()) {
            return currentIndex;
        }
        if (priorTurns == 0) {
            return 0;
        }
        return "advance".equalsIgnoreCase(action)
                ? Math.min(currentIndex + 1, questions.size() - 1)
                : currentIndex;
    }

}

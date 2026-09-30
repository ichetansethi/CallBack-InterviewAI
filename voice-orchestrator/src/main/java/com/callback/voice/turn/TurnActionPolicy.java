package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.session.InterviewSessionState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic rules for which actions a turn may take, as a pure function of session state.
 * TurnDecisionService lists the result in the prompt AND rejects any tool call outside it (the
 * model then gets another attempt within the turn budget) — so a guard can never be talked past,
 * and the prompt can never promise something the validation refuses.
 *
 * <p>A disallowed decision is rejected rather than rewritten: the action and the spoken
 * responseText come from the same tool call, and turning a follow_up into an advance after the
 * fact would speak a follow-up question while moving the cursor to the next one.
 *
 * <p>With prepared questions, the interview ends when the last one has been discussed (or the
 * candidate asks to stop); follow-ups are capped per question so no question can crowd out the
 * rest. The exchange cap is only a safety net: it equals the most exchanges the other rules allow
 * (opener + per question up to MAX_FOLLOW_UPS follow-ups and one move-on), so it only fires if they
 * failed. Without prepared questions there is nothing else to end on, so the old fixed cap stays.
 */
public final class TurnActionPolicy {

    public static final String FOLLOW_UP = "follow_up";
    public static final String ADVANCE = "advance";
    public static final String END = "end";

    public static final int MAX_FOLLOW_UPS_PER_QUESTION = 3;
    /** Sessions with no prepared questions: the model ends on judgment, hard-stopped here. */
    static final int NO_QUESTIONS_MAX_EXCHANGES = 8;

    private TurnActionPolicy() {
    }

    /** The exchange being decided now (1-based), i.e. the turn that will be saved next. */
    public static int currentExchange(InterviewSessionState state) {
        return state.turnCount() + 1;
    }

    public static int safetyCapExchanges(InterviewSessionState state) {
        List<InterviewQuestionDto> questions = state.questions();
        return questions == null || questions.isEmpty()
                ? NO_QUESTIONS_MAX_EXCHANGES
                : 1 + questions.size() * (MAX_FOLLOW_UPS_PER_QUESTION + 1);
    }

    public static boolean safetyCapReached(InterviewSessionState state) {
        return currentExchange(state) >= safetyCapExchanges(state);
    }

    public static boolean followUpLimitReached(InterviewSessionState state) {
        return state.followUpCountForCurrentQuestion() >= MAX_FOLLOW_UPS_PER_QUESTION;
    }

    public static boolean onLastQuestion(InterviewSessionState state) {
        List<InterviewQuestionDto> questions = state.questions();
        return questions != null && !questions.isEmpty() && state.turnCount() > 0
                && state.currentQuestionIndex() >= questions.size() - 1;
    }

    /** In a stable order (follow_up, advance, end), so the prompt renders it consistently. */
    public static Set<String> allowedActions(InterviewSessionState state) {
        List<InterviewQuestionDto> questions = state.questions();
        if (safetyCapReached(state)) {
            return Set.of(END);
        }
        if (questions == null || questions.isEmpty()) {
            return ordered(FOLLOW_UP, ADVANCE, END);
        }
        if (state.turnCount() == 0) {
            return Set.of(ADVANCE); // the opener asks question 1
        }
        boolean canFollowUp = !followUpLimitReached(state);
        if (onLastQuestion(state)) {
            return canFollowUp ? ordered(FOLLOW_UP, END) : Set.of(END);
        }
        return canFollowUp ? ordered(FOLLOW_UP, ADVANCE, END) : ordered(ADVANCE, END);
    }

    private static Set<String> ordered(String... actions) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(actions)));
    }
}

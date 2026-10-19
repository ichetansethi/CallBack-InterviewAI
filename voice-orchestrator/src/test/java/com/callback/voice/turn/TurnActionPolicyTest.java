package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.TurnRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TurnActionPolicyTest {

    private static final List<InterviewQuestionDto> THREE = List.of(
            new InterviewQuestionDto("a", "q1", "r1"),
            new InterviewQuestionDto("b", "q2", "r2"),
            new InterviewQuestionDto("c", "q3", "r3"));

    private static InterviewSessionState afterOpening() {
        return InterviewSessionState.newSession("s", "a@test.com", THREE).withTurn(new TurnRecord("hi", "advance", "q1"));
    }

    private static InterviewSessionState withFollowUps(InterviewSessionState state, int n) {
        for (int i = 0; i < n; i++) {
            state = state.withTurn(new TurnRecord("vague", "follow_up", "probe " + i));
        }
        return state;
    }

    @Test
    void openingAllowsOnlyAdvance() {
        assertThat(TurnActionPolicy.allowedActions(InterviewSessionState.newSession("s", "a@test.com", THREE)))
                .containsExactly("advance");
    }

    @Test
    void midQuestionUnderTheCapAllowsEverything() {
        assertThat(TurnActionPolicy.allowedActions(withFollowUps(afterOpening(), 1)))
                .containsExactly("follow_up", "advance", "end");
    }

    @Test
    void midQuestionAtTheCapForbidsAnotherFollowUp() {
        InterviewSessionState atCap = withFollowUps(afterOpening(), TurnActionPolicy.MAX_FOLLOW_UPS_PER_QUESTION);

        assertThat(TurnActionPolicy.allowedActions(atCap)).containsExactly("advance", "end");
    }

    @Test
    void theCapResetsOnTheNextQuestion() {
        InterviewSessionState nextQuestion = withFollowUps(afterOpening(), 2).withTurn(new TurnRecord("ok", "advance", "q2"));

        assertThat(TurnActionPolicy.allowedActions(nextQuestion)).containsExactly("follow_up", "advance", "end");
    }

    @Test
    void theLastQuestionNeverAllowsAdvanceAndForcesEndAtTheCap() {
        InterviewSessionState onLast = afterOpening()
                .withTurn(new TurnRecord("a1", "advance", "q2"))
                .withTurn(new TurnRecord("a2", "advance", "q3"));

        assertThat(TurnActionPolicy.allowedActions(onLast)).containsExactly("follow_up", "end");
        assertThat(TurnActionPolicy.allowedActions(withFollowUps(onLast, 2))).containsExactly("end");
    }

    @Test
    void safetyCapIsTheMostExchangesTheOtherRulesAllowAndForcesEnd() {
        assertThat(TurnActionPolicy.safetyCapExchanges(afterOpening())).isEqualTo(1 + 3 * 3);

        // Worst case the other guards permit: every question gets 2 follow-ups before moving on.
        InterviewSessionState state = withFollowUps(afterOpening(), 2);
        state = withFollowUps(state.withTurn(new TurnRecord("x", "advance", "q2")), 2);
        state = withFollowUps(state.withTurn(new TurnRecord("x", "advance", "q3")), 2);
        // Opener + 3 × (2 follow-ups + 1 move-on) − the final move-on, which is the exchange being decided.
        assertThat(TurnActionPolicy.currentExchange(state)).isEqualTo(10);
        assertThat(TurnActionPolicy.followUpLimitReached(state)).isTrue();
        assertThat(TurnActionPolicy.allowedActions(state)).containsExactly("end");
        // The cap lands exactly on that final exchange — it never cuts short an interview the other
        // guards would have let finish.
        assertThat(TurnActionPolicy.safetyCapExchanges(state)).isEqualTo(TurnActionPolicy.currentExchange(state));

        // If the guards were bypassed (e.g. history from before they existed), the cap still stops it.
        InterviewSessionState runaway = afterOpening();
        for (int i = 0; i < 8; i++) {
            runaway = runaway.withTurn(new TurnRecord("x", "advance", "clamped"));
        }
        assertThat(TurnActionPolicy.currentExchange(runaway)).isEqualTo(10);
        assertThat(TurnActionPolicy.safetyCapReached(runaway)).isTrue();
        assertThat(TurnActionPolicy.allowedActions(runaway)).containsExactly("end");
    }

    @Test
    void sessionsWithoutPreparedQuestionsKeepTheFixedCap() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", List.of());
        assertThat(TurnActionPolicy.allowedActions(state)).containsExactly("follow_up", "advance", "end");

        for (int i = 0; i < 7; i++) {
            state = state.withTurn(new TurnRecord("x", "follow_up", "y"));
        }
        assertThat(TurnActionPolicy.currentExchange(state)).isEqualTo(TurnActionPolicy.NO_QUESTIONS_MAX_EXCHANGES);
        assertThat(TurnActionPolicy.allowedActions(state)).containsExactly("end");
    }

    @Test
    void sevenQuestionsGetTheirFullFollowUpAllowance() {
        List<InterviewQuestionDto> seven = IntStream.range(0, 7)
                .mapToObj(i -> new InterviewQuestionDto("c", "q" + i, "r" + i)).toList();

        assertThat(TurnActionPolicy.safetyCapExchanges(InterviewSessionState.newSession("s", "a@test.com", seven)))
                .isEqualTo(22);
    }

    @Test
    void followUpIsAllowedAtCountOneAndNotAtTwo() {
        assertThat(TurnActionPolicy.allowedActions(withFollowUps(afterOpening(), 1))).contains("follow_up");
        assertThat(TurnActionPolicy.allowedActions(withFollowUps(afterOpening(), 2))).doesNotContain("follow_up");
    }

    @Test
    void theLastQuestionAllowsFollowUpOrEndBelowTheCap() {
        InterviewSessionState onLast = afterOpening()
                .withTurn(new TurnRecord("a1", "advance", "q2"))
                .withTurn(new TurnRecord("a2", "advance", "q3"));

        assertThat(TurnActionPolicy.allowedActions(onLast)).containsExactly("follow_up", "end");
        assertThat(TurnActionPolicy.allowedActions(withFollowUps(onLast, 1))).containsExactly("follow_up", "end");
    }

    @Test
    void theSafetyCapExchangeAllowsOnlyEnd() {
        InterviewSessionState state = afterOpening();
        while (TurnActionPolicy.currentExchange(state) < TurnActionPolicy.safetyCapExchanges(state) - 1) {
            state = state.withTurn(new TurnRecord("x", "advance", "q"));
        }
        assertThat(TurnActionPolicy.allowedActions(state)).hasSizeGreaterThan(1);
        state = state.withTurn(new TurnRecord("x", "advance", "q"));

        assertThat(TurnActionPolicy.currentExchange(state)).isEqualTo(TurnActionPolicy.safetyCapExchanges(state));
        assertThat(TurnActionPolicy.allowedActions(state)).containsExactly("end");
    }
}

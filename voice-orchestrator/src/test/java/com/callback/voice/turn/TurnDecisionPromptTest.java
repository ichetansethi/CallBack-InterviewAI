package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.TurnRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the question context the model sees, since the cursor fix depends on this wording. */
class TurnDecisionPromptTest {

    private static final List<InterviewQuestionDto> THREE_QUESTIONS = List.of(
            new InterviewQuestionDto("background", "Tell me about yourself", "warm-up"),
            new InterviewQuestionDto("technical", "Describe a challenging project", "depth"),
            new InterviewQuestionDto("closing", "Any questions for us?", "wrap-up"));

    @Test
    void openingExchangeSaysNothingHasBeenAskedAndOffersOnlyQuestionOne() {
        InterviewSessionState opening = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS);

        String prompt = TurnDecisionService.buildTurnPrompt("hi, I'm ready", opening);

        assertThat(prompt).contains("OPENING EXCHANGE: no prepared question has been asked yet")
                .contains("ask prepared question 1 of 3 [background]: Tell me about yourself")
                .doesNotContain("Describe a challenging project");
    }

    @Test
    void laterExchangesShowTheAlreadyAskedQuestionAndTheNextOneToAdvanceTo() {
        InterviewSessionState afterOpening = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "Tell me about yourself"));

        String prompt = TurnDecisionService.buildTurnPrompt("I build APIs", afterOpening);

        assertThat(prompt).contains("CURRENTLY BEING DISCUSSED — already asked (1 of 3) [background]: Tell me about yourself")
                .contains("If you advance, ask the next prepared question [technical]: Describe a challenging project")
                .doesNotContain("OPENING EXCHANGE");
    }

    @Test
    void lastQuestionIsFlaggedOnlyOnceItHasActuallyBeenAsked() {
        InterviewSessionState onSecond = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("a1", "advance", "q2"));
        InterviewSessionState onLast = onSecond.withTurn(new TurnRecord("a2", "advance", "q3"));

        assertThat(TurnDecisionService.buildTurnPrompt("a2", onSecond)).doesNotContain("LAST prepared question")
                .contains("ask the next prepared question [closing]: Any questions for us?");
        assertThat(TurnDecisionService.buildTurnPrompt("a3", onLast)).contains("(3 of 3)")
                .contains("This is the LAST prepared question — there is nothing to advance to")
                .contains("ALLOWED ACTIONS THIS TURN: follow_up, end");
    }

    @Test
    void showsTheFollowUpCountAndTheAllowedActionsTheValidationEnforces() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("a", "follow_up", "f1"));

        assertThat(TurnDecisionService.buildTurnPrompt("b", state))
                .contains("FOLLOW-UPS ALREADY ASKED ON THIS QUESTION: 1 of 3")
                .contains("ALLOWED ACTIONS THIS TURN: follow_up, advance, end")
                .doesNotContain("follow-up limit");
    }

    @Test
    void saysSoWhenTheFollowUpLimitIsReached() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("a", "follow_up", "f1"))
                .withTurn(new TurnRecord("b", "follow_up", "f2"))
                .withTurn(new TurnRecord("c", "follow_up", "f3"));

        assertThat(TurnDecisionService.buildTurnPrompt("d", state))
                .contains("FOLLOW-UPS ALREADY ASKED ON THIS QUESTION: 3 of 3")
                .contains("ALLOWED ACTIONS THIS TURN: advance, end — the follow-up limit for this question is reached");
    }

    @Test
    void openingAllowsOnlyAdvance() {
        assertThat(TurnDecisionService.buildTurnPrompt("hi",
                InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS)))
                .contains("ALLOWED ACTIONS THIS TURN: advance\n");
    }
}

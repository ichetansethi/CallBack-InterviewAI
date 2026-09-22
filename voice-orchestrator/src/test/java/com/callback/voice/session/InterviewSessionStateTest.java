package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionStateTest {

    private static final List<InterviewQuestionDto> THREE_QUESTIONS = List.of(
            new InterviewQuestionDto("background", "Tell me about yourself", "warm-up"),
            new InterviewQuestionDto("technical", "Describe a challenging project", "depth"),
            new InterviewQuestionDto("closing", "Any questions for us?", "wrap-up"));

    @Test
    void advanceMovesToTheNextQuestion() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS);

        InterviewSessionState afterAdvance = state.withTurn(new TurnRecord("answer", "advance", "next question"));

        assertThat(afterAdvance.currentQuestionIndex()).isEqualTo(1);
    }

    @Test
    void followUpDoesNotMoveTheQuestionIndex() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS);

        InterviewSessionState afterFollowUp = state.withTurn(new TurnRecord("answer", "follow_up", "probe more"));

        assertThat(afterFollowUp.currentQuestionIndex()).isEqualTo(0);
    }

    @Test
    void advanceIsClampedAtTheLastQuestionEvenIfTheModelKeepsSayingAdvance() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS);

        InterviewSessionState result = state
                .withTurn(new TurnRecord("a1", "advance", "q2"))
                .withTurn(new TurnRecord("a2", "advance", "q3"))
                .withTurn(new TurnRecord("a3", "advance", "should not go out of bounds"));

        assertThat(result.currentQuestionIndex()).isEqualTo(2);
        assertThat(result.turnCount()).isEqualTo(3);
    }

    @Test
    void advanceWithNoQuestionsConfiguredLeavesIndexAtZero() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", List.of());

        InterviewSessionState result = state.withTurn(new TurnRecord("answer", "advance", "next"));

        assertThat(result.currentQuestionIndex()).isEqualTo(0);
    }

    @Test
    void advanceWithNullQuestionsDoesNotThrow() {
        // Simulates a state deserialized from Redis data written before the questions field
        // existed — Jackson leaves it null rather than failing on the missing property.
        InterviewSessionState state = new InterviewSessionState("s1", "a@test.com", 0, List.of(),
                null, 0, java.time.Instant.now(), java.time.Instant.now());

        InterviewSessionState result = state.withTurn(new TurnRecord("answer", "advance", "next"));

        assertThat(result.currentQuestionIndex()).isEqualTo(0);
    }

}

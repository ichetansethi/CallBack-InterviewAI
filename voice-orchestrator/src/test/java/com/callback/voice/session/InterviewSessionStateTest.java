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
    void theOpeningExchangeAsksQuestionOneWhateverTheModelLabelledIt() {
        // Regression: the model opens with "advance" while asking question 1. That used to move the
        // cursor to question 2, leaving it one ahead of what was spoken for the whole interview.
        for (String openingAction : List.of("advance", "follow_up")) {
            InterviewSessionState afterOpening = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                    .withTurn(new TurnRecord("hi, ready", openingAction, "Tell me about yourself"));

            assertThat(afterOpening.currentQuestionIndex()).as(openingAction).isEqualTo(0);
        }
    }

    @Test
    void advanceAfterTheOpeningMovesToTheNextQuestion() {
        InterviewSessionState afterAdvance = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "Tell me about yourself"))
                .withTurn(new TurnRecord("answer", "advance", "Describe a challenging project"));

        assertThat(afterAdvance.currentQuestionIndex()).isEqualTo(1);
    }

    @Test
    void followUpDoesNotMoveTheQuestionIndex() {
        InterviewSessionState afterFollowUp = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "Tell me about yourself"))
                .withTurn(new TurnRecord("answer", "follow_up", "probe more"));

        assertThat(afterFollowUp.currentQuestionIndex()).isEqualTo(0);
    }

    @Test
    void everyPreparedQuestionIsReachableInOrder() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "q1"));
        assertThat(state.currentQuestionIndex()).isEqualTo(0);
        state = state.withTurn(new TurnRecord("a1", "advance", "q2"));
        assertThat(state.currentQuestionIndex()).isEqualTo(1);
        state = state.withTurn(new TurnRecord("a2", "advance", "q3"));
        assertThat(state.currentQuestionIndex()).isEqualTo(2);
    }

    @Test
    void advanceIsClampedAtTheLastQuestionEvenIfTheModelKeepsSayingAdvance() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS);

        InterviewSessionState result = state
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("a1", "advance", "q2"))
                .withTurn(new TurnRecord("a2", "advance", "q3"))
                .withTurn(new TurnRecord("a3", "advance", "should not go out of bounds"));

        assertThat(result.currentQuestionIndex()).isEqualTo(2);
        assertThat(result.turnCount()).isEqualTo(4);
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
                null, 0, 0, java.time.Instant.now(), java.time.Instant.now());

        InterviewSessionState result = state.withTurn(new TurnRecord("answer", "advance", "next"));

        assertThat(result.currentQuestionIndex()).isEqualTo(0);
    }

    @Test
    void followUpsAreCountedPerQuestionAndResetWhenTheCursorMoves() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "q1"));
        assertThat(state.followUpCountForCurrentQuestion()).isZero();

        state = state.withTurn(new TurnRecord("a", "follow_up", "f1"))
                .withTurn(new TurnRecord("b", "follow_up", "f2"));
        assertThat(state.currentQuestionIndex()).isZero();
        assertThat(state.followUpCountForCurrentQuestion()).isEqualTo(2);

        state = state.withTurn(new TurnRecord("c", "advance", "q2"));
        assertThat(state.currentQuestionIndex()).isEqualTo(1);
        assertThat(state.followUpCountForCurrentQuestion()).isZero();

        state = state.withTurn(new TurnRecord("d", "follow_up", "f1 on q2"));
        assertThat(state.followUpCountForCurrentQuestion()).isEqualTo(1);
    }

    @Test
    void anOpenerLabelledFollowUpIsNotCountedAsAFollowUp() {
        InterviewSessionState state = InterviewSessionState.newSession("s1", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "follow_up", "q1"));

        assertThat(state.followUpCountForCurrentQuestion()).isZero();
    }

    @Test
    void aSessionSavedBeforeTheFollowUpCountExistedDeserializesWithZero() throws Exception {
        // Same ObjectMapper setup Spring Boot gives RedisConfig (JavaTimeModule for the Instants).
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
        String legacyJson = """
                {"sessionId":"a@test.com::qs","ownerEmail":"a@test.com","turnCount":2,
                 "history":[{"transcript":"hi","action":"advance","responseText":"q1"},
                            {"transcript":"a","action":"follow_up","responseText":"f1"}],
                 "questions":[{"category":"c","questionText":"q1","rationale":"r"}],
                 "currentQuestionIndex":0,
                 "createdAt":"2026-09-30T08:00:00Z","updatedAt":"2026-09-30T08:01:00Z"}""";

        InterviewSessionState state = mapper.readValue(legacyJson, InterviewSessionState.class);

        assertThat(state.turnCount()).isEqualTo(2);
        assertThat(state.followUpCountForCurrentQuestion()).isZero();
    }

}

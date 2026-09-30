package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.SessionCompletedEvent;
import com.callback.voice.DTO.TranscriptTurnDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCompletedEventMapperTest {

    private static final List<InterviewQuestionDto> THREE_QUESTIONS = List.of(
            new InterviewQuestionDto("background", "Tell me about yourself", "warm-up"),
            new InterviewQuestionDto("technical", "Describe a challenging project", "depth"),
            new InterviewQuestionDto("closing", "Any questions for us?", "wrap-up"));

    @Test
    void flattensEachExchangeIntoCandidateThenCoachWithTheRationaleOfTheQuestionBeingAsked() {
        InterviewSessionState state = InterviewSessionState.newSession("a@test.com::qs", "a@test.com", THREE_QUESTIONS)
                .withTurn(new TurnRecord("hi", "advance", "Tell me about yourself"))           // opener asks Q1
                .withTurn(new TurnRecord("I build APIs", "advance", "Describe a challenging project")) // Q2
                .withTurn(new TurnRecord("A migration", "follow_up", "What broke?"))           // still Q2
                .withTurn(new TurnRecord("The cutover", "advance", "Any questions for us?"))   // Q3
                .withTurn(new TurnRecord("None", "advance", "One more on Q3?"))                // clamped at Q3
                .withTurn(new TurnRecord("No thanks", "end", "Thanks, that's all for today.")); // closing remark

        UUID historyId = UUID.randomUUID();
        UUID questionSetId = UUID.randomUUID();
        SessionCompletedEvent event = SessionCompletedEventMapper.toEvent(state, historyId, questionSetId);

        assertThat(event.sessionId()).isEqualTo(historyId);
        assertThat(event.questionSetId()).isEqualTo(questionSetId);
        assertThat(event.ownerEmail()).isEqualTo("a@test.com");
        assertThat(event.startedAt()).isEqualTo(state.createdAt());
        assertThat(event.endedAt()).isEqualTo(state.updatedAt());

        List<TranscriptTurnDto> t = event.transcript();
        assertThat(t).extracting(TranscriptTurnDto::turnIndex).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(t).extracting(TranscriptTurnDto::speaker).containsExactly(
                "CANDIDATE", "COACH", "CANDIDATE", "COACH", "CANDIDATE", "COACH",
                "CANDIDATE", "COACH", "CANDIDATE", "COACH", "CANDIDATE", "COACH");
        assertThat(t).extracting(TranscriptTurnDto::text).containsExactly(
                "hi", "Tell me about yourself", "I build APIs", "Describe a challenging project",
                "A migration", "What broke?", "The cutover", "Any questions for us?",
                "None", "One more on Q3?", "No thanks", "Thanks, that's all for today.");
        assertThat(t).extracting(TranscriptTurnDto::questionRationale).containsExactly(
                null, "warm-up", null, "depth", null, "depth", null, "wrap-up", null, "wrap-up", null, null);
    }

    @Test
    void leavesRationaleNullWhenTheSessionHadNoPreparedQuestions() {
        InterviewSessionState noQuestions = InterviewSessionState.newSession("s", "a@test.com", List.of())
                .withTurn(new TurnRecord("hi", "advance", "q"));
        InterviewSessionState legacyNull = new InterviewSessionState("s", "a@test.com", 0, List.of(), null, 0, 0,
                noQuestions.createdAt(), noQuestions.updatedAt())
                .withTurn(new TurnRecord("hi", "follow_up", "q"));

        for (InterviewSessionState state : List.of(noQuestions, legacyNull)) {
            assertThat(SessionCompletedEventMapper.toEvent(state, UUID.randomUUID(), UUID.randomUUID()).transcript())
                    .extracting(TranscriptTurnDto::questionRationale).containsOnlyNulls();
        }
    }

    @Test
    void emptyHistoryProducesAnEmptyTranscript() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", THREE_QUESTIONS);

        assertThat(SessionCompletedEventMapper.toEvent(state, UUID.randomUUID(), UUID.randomUUID()).transcript()).isEmpty();
    }
}

package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.SessionCompletedEvent;
import com.callback.voice.DTO.TranscriptTurnDto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Flattens a finished interview's history into the session-completed wire shape. Each TurnRecord
 * is one exchange — the candidate's utterance, then the interviewer's reply — so it becomes a
 * CANDIDATE turn followed by a COACH turn.
 *
 * <p>TurnRecord doesn't store which prepared question the reply was on, so it's recovered by
 * replaying InterviewSessionState.nextQuestionIndex over the history from the start — the exact
 * rule the live session used to move its cursor. The opening reply asks question 1; after a
 * follow_up the reply is still probing the same question; after an advance it's asking the next
 * one; an end reply is a closing remark, not a question, so it carries no rationale.
 */
public final class SessionCompletedEventMapper {

    static final String CANDIDATE = "CANDIDATE";
    static final String COACH = "COACH";

    private SessionCompletedEventMapper() {
    }

    public static SessionCompletedEvent toEvent(InterviewSessionState state, UUID historySessionId, UUID questionSetId) {
        List<InterviewQuestionDto> questions = state.questions();
        List<TranscriptTurnDto> transcript = new ArrayList<>();
        int questionIndex = 0;
        int turnIndex = 0;
        int priorTurns = 0;

        for (TurnRecord turn : state.history()) {
            transcript.add(new TranscriptTurnDto(CANDIDATE, turn.transcript(), null, turnIndex++));

            questionIndex = InterviewSessionState.nextQuestionIndex(priorTurns++, questionIndex, turn.action(), questions);
            String rationale = "end".equalsIgnoreCase(turn.action()) || questions == null || questions.isEmpty()
                    ? null
                    : questions.get(questionIndex).rationale();
            transcript.add(new TranscriptTurnDto(COACH, turn.responseText(), rationale, turnIndex++));
        }

        return new SessionCompletedEvent(historySessionId, state.ownerEmail(), questionSetId,
                List.copyOf(transcript), state.createdAt(), state.updatedAt());
    }
}

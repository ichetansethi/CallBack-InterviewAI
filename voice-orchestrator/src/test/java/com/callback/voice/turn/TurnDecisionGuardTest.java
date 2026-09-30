package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.TurnRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the TurnActionPolicy guards are enforced in code, not just stated in the prompt: a model
 * scripted (via StubGroqServer) to break the rules has its decision rejected and gets another
 * attempt, and a model that never complies can't get a disallowed action through at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnDecisionGuardTest {

    private static final Duration BUDGET = Duration.ofSeconds(6);
    private static final StubGroqServer STUB = new StubGroqServer();

    @DynamicPropertySource
    static void pointAtStub(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", STUB::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
        registry.add("turn-decision.max-turn-duration", BUDGET::toString);
    }

    private static final List<InterviewQuestionDto> THREE = List.of(
            new InterviewQuestionDto("a", "q1", "r1"),
            new InterviewQuestionDto("b", "q2", "r2"),
            new InterviewQuestionDto("c", "q3", "r3"));

    @Autowired
    private TurnDecisionService turnDecisionService;

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    private static InterviewSessionState firstQuestionAtFollowUpCap() {
        return InterviewSessionState.newSession("s", "a@test.com", THREE)
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("vague", "follow_up", "f1"))
                .withTurn(new TurnRecord("vague", "follow_up", "f2"))
                .withTurn(new TurnRecord("vague", "follow_up", "f3"));
    }

    @Test
    void aFourthFollowUpIsRejectedAndTheModelsCompliantRetryIsAccepted() {
        STUB.thenDecision("follow_up", "Could you say more?");
        STUB.thenDecision("advance", "q2");

        TurnDecision decision = turnDecisionService.decideNextTurn("still vague", firstQuestionAtFollowUpCap())
                .block(Duration.ofSeconds(30));

        assertThat(decision.action()).isEqualTo("advance");
        assertThat(decision.responseText()).isEqualTo("q2");
        assertThat(STUB.requests()).isEqualTo(4); // two full tool-call exchanges
    }

    @Test
    void advanceOnTheLastQuestionIsRejectedInFavourOfEnd() {
        InterviewSessionState onLast = InterviewSessionState.newSession("s", "a@test.com", THREE)
                .withTurn(new TurnRecord("hi", "advance", "q1"))
                .withTurn(new TurnRecord("a1", "advance", "q2"))
                .withTurn(new TurnRecord("a2", "advance", "q3"));
        STUB.thenDecision("advance", "Next question...");
        STUB.thenDecision("end", "Thanks, that's all for today.");

        TurnDecision decision = turnDecisionService.decideNextTurn("done", onLast).block(Duration.ofSeconds(30));

        assertThat(decision.action()).isEqualTo("end");
        assertThat(STUB.requests()).isEqualTo(4);
    }

    @Test
    void aModelThatNeverCompliesCannotGetADisallowedActionThrough() {
        for (int i = 0; i < 10; i++) {
            STUB.thenDecision("follow_up", "One more follow-up?");
        }

        assertThatThrownBy(() -> turnDecisionService.decideNextTurn("still vague", firstQuestionAtFollowUpCap())
                .block(Duration.ofSeconds(30)))
                .hasRootCauseMessage("Model chose 'follow_up' but only [advance, end] are allowed this turn");
    }

    @Test
    void actionIsNormalisedToLowerCase() {
        STUB.thenDecision("ADVANCE", "q2");

        TurnDecision decision = turnDecisionService.decideNextTurn("ok", firstQuestionAtFollowUpCap())
                .block(Duration.ofSeconds(30));

        assertThat(decision.action()).isEqualTo("advance");
    }
}

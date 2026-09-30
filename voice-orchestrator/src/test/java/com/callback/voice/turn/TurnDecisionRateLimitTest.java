package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
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
 * Proves TurnDecisionService's per-turn budget through the real Spring AI OpenAI client and the
 * copied ChatModelErrorHandlingConfig, against StubGroqServer — a real 429 can't be produced on
 * demand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnDecisionRateLimitTest {

    private static final Duration BUDGET = Duration.ofSeconds(6);
    private static final StubGroqServer STUB = new StubGroqServer();

    @DynamicPropertySource
    static void pointAtStub(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", STUB::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
        registry.add("turn-decision.max-turn-duration", BUDGET::toString);
    }

    @Autowired
    private TurnDecisionService turnDecisionService;

    private final InterviewSessionState opening = InterviewSessionState.newSession("s", "a@test.com",
            List.of(new InterviewQuestionDto("technical", "How do you size Kafka partitions?", "JD: Kafka")));

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void waitsOutAShortRetryAfterThenSucceeds() {
        STUB.then(StubGroqServer.Response.rateLimited("1"));
        STUB.thenDecision("advance", "How do you size Kafka partitions?");

        long start = System.nanoTime();
        TurnDecision decision = turnDecisionService.decideNextTurn("Hi", opening).block(Duration.ofSeconds(30));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(decision.action()).isEqualTo("advance");
        assertThat(decision.responseText()).isEqualTo("How do you size Kafka partitions?");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(1)).isLessThan(BUDGET);
        assertThat(STUB.requests()).isEqualTo(3); // 429, tool call, post-tool final message
        assertThat(STUB.toolArgNames()).containsExactly("action", "responseText");
    }

    @Test
    void aRetryAfterBeyondTheBudgetFailsTheTurnImmediately() {
        STUB.then(StubGroqServer.Response.rateLimited("60"));

        long start = System.nanoTime();
        assertThatThrownBy(() -> turnDecisionService.decideNextTurn("Hi", opening).block(Duration.ofSeconds(30)))
                .hasMessageContaining("turn budget");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(STUB.requests()).isEqualTo(1);
    }

    @Test
    void repeated429sWithoutRetryAfterGiveUpWithinTheBudget() {
        for (int i = 0; i < 10; i++) {
            STUB.then(StubGroqServer.Response.rateLimited(null));
        }

        long start = System.nanoTime();
        assertThatThrownBy(() -> turnDecisionService.decideNextTurn("Hi", opening).block(Duration.ofSeconds(30)))
                .hasMessageContaining("turn budget");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 429 → 2s fallback wait → 429 → a 4s wait would leave under MIN_ATTEMPT_TIME → give up.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(2)).isLessThan(BUDGET);
        assertThat(STUB.requests()).isEqualTo(2);
    }
}

package com.callback.compatibility.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the scorer fails, with the model mocked and backoff waits at zero. Both cases were seen on
 * 2026-10-13 while Groq's daily token quota was spent: every judgment vote failed and the
 * requirement was counted as met, so an analysis could complete with inflated scores; and each
 * 429 was retried every 12s, so one analysis ran for over half an hour.
 */
class CompatibilityScorerFailureTest {

    private static final List<RequirementEvidence> EVIDENCE =
            List.of(new RequirementEvidence("Kafka", List.of("Built Kafka consumers")));

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final CompatibilityScorer scorer = new CompatibilityScorer(chatClient, new RateLimitBackoff(0, 0));

    @Test
    void aRequirementWhoseVotesAllFailFailsTheAnalysisInsteadOfCountingAsMet() {
        // The model answers in prose and never calls recordJudgment, on every attempt of every vote.
        when(chatClient.prompt().system(anyString()).user(anyString()).tools(any()).call().content())
                .thenReturn("Looks fine to me.");

        assertThatThrownBy(() -> scorer.score("JD text", EVIDENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("All 3 judgment votes failed for requirement \"Kafka\"");
    }

    @Test
    void aSpentDailyQuotaFailsTheAnalysisAtTheFirst429() {
        when(chatClient.prompt().system(anyString()).user(anyString()).tools(any()).call().content())
                .thenThrow(RequirementExtractorRetryTest.TPD_LIMIT)
                .thenReturn("never reached");

        assertThatThrownBy(() -> scorer.score("JD text", EVIDENCE))
                .isInstanceOf(GroqQuotaExhaustedException.class)
                .hasCause(RequirementExtractorRetryTest.TPD_LIMIT);
    }
}

package com.callback.compatibility.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for queued analyses failing in 0s: once the previous analysis had used up
 * Groq's 30 requests/minute, the next one's extraction got a 429 and, with no retry here, failed
 * the whole analysis. The 429 message is the one Groq actually returned. Backoff waits are zero so
 * the test doesn't sleep.
 */
class RequirementExtractorRetryTest {

    private static final NonTransientAiException RPM_LIMIT = new NonTransientAiException(
            "HTTP 429 - {\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-120b` "
                    + "on requests per minute (RPM): Limit 30, Used 30, Requested 1.\","
                    + "\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}");

    /** What Groq returned on 2026-10-13 once the daily token quota was spent. */
    static final NonTransientAiException TPD_LIMIT = new NonTransientAiException(
            "HTTP 429 - {\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-120b` "
                    + "service tier `on_demand` on tokens per day (TPD): Limit 200000, Used 199869, "
                    + "Requested 2573. Please try again in 17m34.944s.\",\"type\":\"tokens\","
                    + "\"code\":\"rate_limit_exceeded\"}}");

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final RequirementExtractor extractor = new RequirementExtractor(chatClient, new RateLimitBackoff(0, 0));

    @Test
    void aRateLimitedExtractionIsRetriedInsteadOfFailingTheAnalysis() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(RequirementList.class))
                .thenThrow(RPM_LIMIT)
                .thenThrow(RPM_LIMIT)
                .thenReturn(new RequirementList(List.of("Java 21", "Kafka")));

        assertThat(extractor.extract("JD text")).containsExactly("Java 21", "Kafka");
    }

    @Test
    void givesUpAfterFourAttemptsWithTheLastFailure() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(RequirementList.class))
                .thenThrow(RPM_LIMIT);

        assertThatThrownBy(() -> extractor.extract("JD text")).isSameAs(RPM_LIMIT);
    }

    @Test
    void aSpentDailyQuotaFailsAtOnceInsteadOfBeingRetried() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(RequirementList.class))
                .thenThrow(TPD_LIMIT)
                .thenReturn(new RequirementList(List.of("Java 21")));

        assertThatThrownBy(() -> extractor.extract("JD text"))
                .isInstanceOf(GroqQuotaExhaustedException.class)
                .hasCause(TPD_LIMIT);
    }

    @Test
    void groqsRateLimitMessagesAreRecognised() {
        assertThat(RateLimitBackoff.isRateLimit(RPM_LIMIT)).isTrue();
        assertThat(RateLimitBackoff.isRateLimit(new IllegalStateException("Model did not call submitScores"))).isFalse();
        assertThat(RateLimitBackoff.isDailyQuota(TPD_LIMIT)).isTrue();
        assertThat(RateLimitBackoff.isDailyQuota(RPM_LIMIT)).isFalse();
    }
}

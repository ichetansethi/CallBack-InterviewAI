package com.callback.question.generation;

import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real backOff() logic end-to-end through a RetryTemplate (no network, no mocked
 * sleep) — using small Retry-After values so the Retry-After-honoring path stays fast, and
 * accepting a real ~2s wait for the one fallback-schedule case, to prove the actual sleep
 * durations rather than just the delay-selection logic in isolation.
 */
class RateLimitBackOffPolicyTest {

    private RetryTemplate template(int maxAttempts) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .retryOn(GroqRateLimitException.class)
                .customBackoff(new RateLimitBackOffPolicy())
                .build();
    }

    @Test
    void honorsTheRetryAfterCarriedByTheMostRecentFailure() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();

        assertThatThrownBy(() -> template(3).execute(ctx -> {
            calls.incrementAndGet();
            throw new GroqRateLimitException("HTTP 429", Duration.ofMillis(50));
        })).isInstanceOf(GroqRateLimitException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(calls.get()).isEqualTo(3);
        // 2 backoffs between 3 attempts, ~50ms each per the supplied Retry-After.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(95);
        // Well under the first fallback delay (2s) — proves Retry-After was actually used, not ignored.
        assertThat(elapsedMs).isLessThan(1000);
    }

    @Test
    void fallsBackToTheFixedExponentialScheduleWhenNoRetryAfterIsSent() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> template(2).execute(ctx -> {
            throw new GroqRateLimitException("HTTP 429", null);
        })).isInstanceOf(GroqRateLimitException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // maxAttempts=2 -> exactly one backoff: the first fallback delay, 2s.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1900);
        assertThat(elapsedMs).isLessThan(3500);
    }

    @Test
    void failsAtOnceInsteadOfSleepingWhenRetryAfterExceedsMaxWait() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();

        // The Retry-After Groq sent on 2026-10-13 once the daily token quota was spent: 17m35s.
        assertThatThrownBy(() -> template(4).execute(ctx -> {
            calls.incrementAndGet();
            throw new GroqRateLimitException("HTTP 429 - tokens per day (TPD)", Duration.ofSeconds(1055));
        }))
                .isInstanceOf(GroqQuotaExhaustedException.class)
                .hasMessageContaining("1055s")
                .hasMessageContaining("tokens per day (TPD)")
                .satisfies(e -> assertThat(((GroqQuotaExhaustedException) e).retryAfter())
                        .isEqualTo(Duration.ofSeconds(1055)));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(calls.get()).isEqualTo(1);
        assertThat(elapsedMs).isLessThan(1000);
    }
}

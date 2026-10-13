package com.callback.question.generation;

import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.support.RetrySynchronizationManager;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backoff for GroqRateLimitException specifically: honors the provider's own Retry-After hint on
 * the failure that just happened when it sent one (it knows its own reset window better than a
 * fixed guess does), otherwise falls back to a fixed 2s / 4s / 8s exponential schedule. A
 * Retry-After over MAX_WAIT means the daily quota, not the per-minute one, so it throws
 * GroqQuotaExhaustedException instead of sleeping for minutes. Not a
 * generic backoff policy — RATE_LIMIT_RETRY_TEMPLATE only ever retries GroqRateLimitException, so
 * "the last throwable" is always that type here.
 */
public class RateLimitBackOffPolicy implements BackOffPolicy {

    /** Groq's per-minute limit asks for seconds; this leaves room for the retried call inside
     * QuestionGenerationService.MODEL_CALL_TIMEOUT (60s). */
    static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private static final Duration[] FALLBACK_DELAYS = {
            Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)
    };

    @Override
    public BackOffContext start(RetryContext context) {
        return new Context();
    }

    @Override
    public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
        Context context = (Context) backOffContext;
        int attempt = context.attempt.getAndIncrement();
        Duration delay = resolveDelay(attempt);
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackOffInterruptedException("Interrupted during rate-limit backoff", e);
        }
    }

    private Duration resolveDelay(int attempt) {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        Throwable lastThrowable = retryContext != null ? retryContext.getLastThrowable() : null;
        if (lastThrowable instanceof GroqRateLimitException rateLimitException && rateLimitException.retryAfter() != null) {
            if (rateLimitException.retryAfter().compareTo(MAX_WAIT) > 0) {
                throw new GroqQuotaExhaustedException(rateLimitException);
            }
            return rateLimitException.retryAfter();
        }
        return FALLBACK_DELAYS[Math.min(attempt, FALLBACK_DELAYS.length - 1)];
    }

    private static final class Context implements BackOffContext {
        private final AtomicInteger attempt = new AtomicInteger(0);
    }
}

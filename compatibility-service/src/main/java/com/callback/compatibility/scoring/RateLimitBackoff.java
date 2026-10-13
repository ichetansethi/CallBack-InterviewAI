package com.callback.compatibility.scoring;

/**
 * The pause before retrying a failed Groq call, shared by every model call in the pipeline. A rate
 * limit (429) waits long enough for Groq's per-minute window to free up, so the retry doesn't just
 * re-hit the same limit; any other failure retries almost immediately.
 *
 * Spring AI treats a 429 as non-transient and never retries it itself, so without this a single
 * 429 fails the whole analysis. That happens routinely once analyses are queued: the next one
 * starts the instant the previous one has used up the minute's requests.
 */
class RateLimitBackoff {

    private final long rateLimitWaitMillis;
    private final long otherWaitMillis;

    RateLimitBackoff() {
        this(12_000, 300);
    }

    RateLimitBackoff(long rateLimitWaitMillis, long otherWaitMillis) {
        this.rateLimitWaitMillis = rateLimitWaitMillis;
        this.otherWaitMillis = otherWaitMillis;
    }

    static boolean isRateLimit(RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        return message.contains("429") || message.toLowerCase().contains("rate_limit");
    }

    void waitBeforeRetry(RuntimeException failure) {
        try {
            Thread.sleep(isRateLimit(failure) ? rateLimitWaitMillis : otherWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

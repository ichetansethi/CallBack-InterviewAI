package com.callback.question.generation;

import java.time.Duration;

/**
 * Thrown specifically for an HTTP 429 from the chat model API — deliberately kept distinct from
 * schema/tool-call validation failures (a malformed or missing submitQuestions call), which are a
 * different failure mode: retrying those relies on QuestionGenerationService's own attempt loop
 * giving the model another try, not on waiting out a provider-side rate limit. Carries the
 * provider's Retry-After hint (in seconds) when it sent one, so the rate-limit backoff can honor
 * it instead of guessing.
 */
public class GroqRateLimitException extends RuntimeException {

    private final Duration retryAfter;

    public GroqRateLimitException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** Null if Groq's response didn't carry a (parseable) Retry-After header. */
    public Duration retryAfter() {
        return retryAfter;
    }
}

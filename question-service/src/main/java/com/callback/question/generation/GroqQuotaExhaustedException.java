package com.callback.question.generation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Duration;

/**
 * A 429 whose Retry-After is longer than RateLimitBackOffPolicy.MAX_WAIT. Groq sends seconds for
 * its per-minute limit but minutes once the daily token quota (TPD) is spent, and waiting that
 * out would only end in MODEL_CALL_TIMEOUT's misleading "runaway tool-call loop" message. Thrown
 * instead of sleeping, and not retried by QuestionGenerationService's attempt loop: asking again
 * within minutes gets the same answer.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class GroqQuotaExhaustedException extends GroqRateLimitException {

    public GroqQuotaExhaustedException(GroqRateLimitException cause) {
        super("Groq asked to wait " + cause.retryAfter().toSeconds() + "s before retrying, longer than the "
                + RateLimitBackOffPolicy.MAX_WAIT.toSeconds() + "s this service waits; its daily token quota "
                + "(TPD) is likely used up. " + cause.getMessage(), cause.retryAfter(), cause);
    }
}

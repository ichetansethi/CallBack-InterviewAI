package com.callback.compatibility.scoring;

/**
 * A 429 for Groq's daily limit (tokens or requests per day) rather than its per-minute one. The
 * per-minute window frees up within seconds; the daily one takes minutes to hours, so retrying
 * every few seconds only keeps the analysis running past the consumer's max.poll.interval.ms and
 * gets it kicked out of the group. Thrown at the first such 429 so the analysis fails at once,
 * with a reason that names the quota.
 */
public class GroqQuotaExhaustedException extends RuntimeException {

    public GroqQuotaExhaustedException(RuntimeException cause) {
        super("Groq's daily quota is used up: " + cause.getMessage(), cause);
    }
}

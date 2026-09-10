package com.callback.question.generation;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Replaces Spring AI's default ResponseErrorHandler bean (spring-ai-retry's is
 * {@code @ConditionalOnMissingBean}, so defining this one here takes over) for exactly one
 * reason: that default reads the error body into a plain message string and discards the
 * response's headers entirely, so a 429's Retry-After header is unreachable by the time any
 * exception reaches our code. This handler is otherwise behaviorally identical to the default
 * (same NonTransientAiException/TransientAiException classification for everything else) — it
 * only special-cases 429, capturing Retry-After (when Groq sends one; confirmed via a live probe
 * that it does, as a plain integer-seconds value) into GroqRateLimitException before it's lost.
 */
@Configuration
public class ChatModelErrorHandlingConfig {

    @Bean
    public ResponseErrorHandler responseErrorHandler() {
        return new ResponseErrorHandler() {

            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().isError();
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                if (!response.getStatusCode().isError()) {
                    return;
                }

                String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                if (body == null || body.isEmpty()) {
                    body = "No response body available";
                }
                String message = String.format("HTTP %s - %s", response.getStatusCode().value(), body);

                if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    throw new GroqRateLimitException(message, parseRetryAfter(response.getHeaders()));
                }
                if (response.getStatusCode().is4xxClientError()) {
                    throw new NonTransientAiException(message);
                }
                throw new TransientAiException(message);
            }
        };
    }

    /** Groq sends Retry-After as a plain integer number of seconds (confirmed live), not an
     * HTTP-date — the only form parsed here. Returns null if absent or unparseable, so the
     * fallback exponential schedule takes over instead. */
    private Duration parseRetryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

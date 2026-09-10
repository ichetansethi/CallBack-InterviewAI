package com.callback.question.generation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the one reason this handler exists over Spring AI's default: a 429's Retry-After header
 * survives into GroqRateLimitException instead of being discarded, while every other status code
 * still gets the same classification the default would have given it.
 */
class ChatModelErrorHandlingConfigTest {

    private final ResponseErrorHandler handler = new ChatModelErrorHandlingConfig().responseErrorHandler();

    private ClientHttpResponse mockResponse(HttpStatus status, String retryAfterHeader, String body) throws Exception {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        HttpHeaders headers = new HttpHeaders();
        if (retryAfterHeader != null) {
            headers.add(HttpHeaders.RETRY_AFTER, retryAfterHeader);
        }
        when(response.getHeaders()).thenReturn(headers);
        InputStream bodyStream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        when(response.getBody()).thenReturn(bodyStream);
        return response;
    }

    @Test
    void a429WithARetryAfterHeaderCarriesThatDurationOnGroqRateLimitException() throws Exception {
        ClientHttpResponse response = mockResponse(HttpStatus.TOO_MANY_REQUESTS, "4", "{\"error\":\"rate limited\"}");

        assertThatThrownBy(() -> handler.handleError(response))
                .isInstanceOf(GroqRateLimitException.class)
                .satisfies(e -> assertThat(((GroqRateLimitException) e).retryAfter()).isEqualTo(Duration.ofSeconds(4)));
    }

    @Test
    void a429WithNoRetryAfterHeaderCarriesANullDuration() throws Exception {
        ClientHttpResponse response = mockResponse(HttpStatus.TOO_MANY_REQUESTS, null, "{\"error\":\"rate limited\"}");

        assertThatThrownBy(() -> handler.handleError(response))
                .isInstanceOf(GroqRateLimitException.class)
                .satisfies(e -> assertThat(((GroqRateLimitException) e).retryAfter()).isNull());
    }

    @Test
    void anUnparseableRetryAfterHeaderFallsBackToANullDurationRatherThanFailing() throws Exception {
        ClientHttpResponse response = mockResponse(HttpStatus.TOO_MANY_REQUESTS, "not-a-number", "{\"error\":\"rate limited\"}");

        assertThatThrownBy(() -> handler.handleError(response))
                .isInstanceOf(GroqRateLimitException.class)
                .satisfies(e -> assertThat(((GroqRateLimitException) e).retryAfter()).isNull());
    }

    @Test
    void otherFourXxStatusesStillThrowNonTransientAiExceptionLikeTheDefaultHandlerWould() throws Exception {
        ClientHttpResponse response = mockResponse(HttpStatus.UNAUTHORIZED, null, "{\"error\":\"bad key\"}");

        assertThatThrownBy(() -> handler.handleError(response)).isInstanceOf(NonTransientAiException.class);
    }

    @Test
    void fiveXxStatusesStillThrowTransientAiExceptionLikeTheDefaultHandlerWould() throws Exception {
        ClientHttpResponse response = mockResponse(HttpStatus.SERVICE_UNAVAILABLE, null, "{\"error\":\"down\"}");

        assertThatThrownBy(() -> handler.handleError(response)).isInstanceOf(TransientAiException.class);
    }
}

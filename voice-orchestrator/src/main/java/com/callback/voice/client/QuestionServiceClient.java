package com.callback.voice.client;

import com.callback.voice.DTO.QuestionSetDto;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls question-service's GET /questions/{id} (see QuestionSetController there) to fetch the
 * prepared question list for an interview. question-service enforces ownership itself — it returns
 * 404 for BOTH "no such question set" and "exists but isn't yours" (deliberately the same response
 * for both, per its own QuestionSetService.findOwned), and 401 if the forwarded bearer token isn't
 * accepted. Only the 404 case is folded into QuestionSetNotFoundException here: collapsing a 401
 * into "not found" too would hide a real auth-forwarding bug (e.g. token expired between the
 * candidate's handshake and this call) behind a message that looks like a legitimate access denial.
 *
 * <p>QuestionSetNotFoundException here is voice-orchestrator's own local class (in this package),
 * not question-service's — the two are separate deployable services with no compile-time
 * dependency on each other's internal types.
 */
@Component
public class QuestionServiceClient {

    // Same reasoning as WhisperClientConfig/PiperClientConfig: without an explicit timeout, a
    // question-service that's up but unresponsive would hang the WebSocket handshake indefinitely —
    // worse than the audio-pipeline timeouts, since this happens before anything else even starts.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final WebClient webClient;

    public QuestionServiceClient(WebClient.Builder builder,
                                 @Value("${services.question-service.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = builder.baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<QuestionSetDto> getQuestionSet(UUID questionSetId, String bearerToken) {
        return webClient.get()
                .uri("/questions/{id}", questionSetId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .onStatus(status -> status.equals(HttpStatus.NOT_FOUND),
                        resp -> Mono.error(new QuestionSetNotFoundException(questionSetId)))
                .bodyToMono(QuestionSetDto.class);
    }
}

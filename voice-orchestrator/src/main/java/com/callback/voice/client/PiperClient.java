package com.callback.voice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/** Calls Piper's HTTP server: a JSON POST to /synthesize, raw synthesized audio bytes back. */
@Component
public class PiperClient {

    private final WebClient webClient;

    public PiperClient(@Qualifier("piperWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<byte[]> synthesize(String text) {
        return webClient.post()
                .uri("/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(byte[].class);
    }

}

package com.callback.voice.client;

import com.callback.voice.DTO.WhisperTranscriptionResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Calls whisper.cpp's server mode: a multipart POST to /inference, JSON {"text": "..."} back. */
@Component
public class WhisperClient {

    private final WebClient webClient;

    public WhisperClient(@Qualifier("whisperWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> transcribe(byte[] wavBytes) {
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(wavBytes) {
            @Override
            public String getFilename() {
                return "utterance.wav";
            }
        }).contentType(MediaType.parseMediaType("audio/wav"));

        return webClient.post()
                .uri("/inference")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .bodyToMono(WhisperTranscriptionResponse.class)
                .map(WhisperTranscriptionResponse::text);
    }

}

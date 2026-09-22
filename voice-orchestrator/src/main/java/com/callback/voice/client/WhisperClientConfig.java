package com.callback.voice.client;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WhisperClientConfig {

    // Without an explicit timeout, WebClient/Reactor Netty will wait indefinitely for a response.
    // A whisper.cpp process that's down fails the connection fast (see PiperClientConfig for the
    // same reasoning) — but one that's alive and unresponsive would otherwise hang a turn forever
    // with no fallback ever firing. 15s is generous headroom over real local transcription time.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONNECT_TIMEOUT_MS = 3000;

    @Bean
    public WebClient whisperWebClient(WebClient.Builder builder, @Value("${whisper.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return builder.baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

}

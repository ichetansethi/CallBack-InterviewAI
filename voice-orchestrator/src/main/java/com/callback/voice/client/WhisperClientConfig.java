package com.callback.voice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WhisperClientConfig {

    @Bean
    public WebClient whisperWebClient(WebClient.Builder builder, @Value("${whisper.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

}

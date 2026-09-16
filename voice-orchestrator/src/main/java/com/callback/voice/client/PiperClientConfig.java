package com.callback.voice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class PiperClientConfig {

    @Bean
    public WebClient piperWebClient(WebClient.Builder builder, @Value("${piper.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

}

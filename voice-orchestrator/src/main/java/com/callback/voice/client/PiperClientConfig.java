package com.callback.voice.client;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class PiperClientConfig {

    // WebFlux's default in-memory buffer cap for a fully-buffered response body is 256KB, which a
    // single synthesized sentence's raw audio can exceed. Raised well past any realistic one-sentence
    // clip so PiperClient.synthesize's bodyToMono(byte[].class) never fails on response size alone.
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    // Without an explicit timeout, WebClient/Reactor Netty will wait indefinitely for a response.
    // A Piper process that's down fails the connection fast — but one that's alive and unresponsive
    // would otherwise hang a turn forever with no fallback ever firing. 15s is generous headroom
    // over real local synthesis time for a single sentence.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONNECT_TIMEOUT_MS = 3000;

    @Bean
    public WebClient piperWebClient(WebClient.Builder builder, @Value("${piper.base-url}") String baseUrl) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return builder.baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

}

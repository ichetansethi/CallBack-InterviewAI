package com.callback.compatibility.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class JdResumeServiceClientConfig {

    @Bean
    public RestClient jdResumeServiceRestClient(RestClient.Builder builder,
                                                 @Value("${jd-resume-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}

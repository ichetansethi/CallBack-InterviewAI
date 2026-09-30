package com.callback.session.config;

import com.callback.session.DTO.SessionCompletedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, SessionCompletedEvent> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${session-history.kafka.group-id}") String groupId) {
        // useHeadersIfPresent=false: always bind to SessionCompletedEvent. The producer lives in
        // another service, so any __TypeId__ header it sends names a class that doesn't exist here.
        JsonDeserializer<SessionCompletedEvent> json = new JsonDeserializer<>(SessionCompletedEvent.class, false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // A brand-new group must not skip events published before this service first started.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Wrapping the instance (not just naming the class in props — instances passed here override
        // props) is what turns malformed JSON into a DeserializationException the error handler can
        // skip, instead of a poison pill that fails every poll forever.
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SessionCompletedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, SessionCompletedEvent> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, SessionCompletedEvent>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler());
        return factory;
    }

    /**
     * Retries a failed event 5 times at 2s, 4s, 8s, 16s, 32s (~62s total) — long enough to ride out
     * Groq's per-minute rate limit — then logs and skips it. Safe to retry because the listener is
     * idempotent end to end. DeserializationException (malformed JSON) is non-retryable by default;
     * IllegalArgumentException (a bad field value such as an unknown speaker) is added here, since
     * no amount of waiting fixes either.
     */
    private DefaultErrorHandler errorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(2_000, 2.0);
        backOff.setMaxAttempts(5);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public NewTopic sessionCompletedTopic(@Value("${session-history.kafka.topic}") String topic) {
        // Broker has auto-create disabled (docker-compose.yml), so this is what creates the topic.
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }
}

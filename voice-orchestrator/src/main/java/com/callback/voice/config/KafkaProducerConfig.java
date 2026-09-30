package com.callback.voice.config;

import com.callback.voice.DTO.SessionCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, SessionCompletedEvent> sessionCompletedProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers, ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // KafkaTemplate.send() blocks the calling thread for up to max.block.ms while it fetches
        // metadata (60s by default) — with the broker down that would stall the end of an
        // interview for a minute. Bound the whole send to ~15s so a failure surfaces promptly.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);

        // Boot's ObjectMapper writes Instants as ISO-8601 strings. No __TypeId__ header: it would
        // name this service's class, which the consumer doesn't have (and ignores anyway).
        JsonSerializer<SessionCompletedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, SessionCompletedEvent> sessionCompletedKafkaTemplate(
            ProducerFactory<String, SessionCompletedEvent> sessionCompletedProducerFactory) {
        return new KafkaTemplate<>(sessionCompletedProducerFactory);
    }

    @Bean
    public NewTopic sessionCompletedTopic(@Value("${session-history.kafka.topic}") String topic) {
        // The broker has auto-create disabled (docker-compose.yml). session-history-service declares
        // this topic too, identically — whichever service starts first creates it, so a send never
        // stalls for max.block.ms on a topic that doesn't exist yet.
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }
}

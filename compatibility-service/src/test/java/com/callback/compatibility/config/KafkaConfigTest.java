package com.callback.compatibility.config;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.serializer.SerializationUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the Kafka wiring without publishing anything: a record left on analysis-requested would
 * later be picked up by the real listener. Needs the docker-compose broker running, since startup
 * creates the topic there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KafkaConfigTest {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ProducerFactory<String, AnalysisRequestedEvent> producerFactory;

    @Autowired
    private ConsumerFactory<String, AnalysisRequestedEvent> consumerFactory;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Value("${compatibility.kafka.topic}")
    private String topic;

    /** src/test/resources/config/application.yml keeps test contexts out of the real consumer group. */
    @Test
    void listenerDoesNotStartInOrdinaryTestContexts() {
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty()
                .noneMatch(container -> container.isRunning());
    }

    @Test
    void topicIsCreatedWithThreePartitions() {
        TopicDescription description = kafkaAdmin.describeTopics(topic).get(topic);

        assertThat(description.partitions()).hasSize(3);
    }

    @Test
    void producerIsIdempotentAndFailsFastWhenTheBrokerIsDown() {
        var props = producerFactory.getConfigurationProperties();

        assertThat(props).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(props).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        assertThat(props).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
    }

    @Test
    void consumerTakesOneRecordPerPoll() {
        var props = consumerFactory.getConfigurationProperties();

        assertThat(props).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        assertThat(props).containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
    }

    @Test
    void eventSurvivesTheRoundTripThroughTheConfiguredSerializers() {
        AnalysisRequestedEvent event = new AnalysisRequestedEvent(
                UUID.randomUUID(), "owner@example.com", UUID.randomUUID(), UUID.randomUUID(),
                "JD text", "Resume text", Instant.parse("2026-10-13T05:00:00.123456Z"));

        RecordHeaders headers = new RecordHeaders();
        byte[] bytes = producerFactory.getValueSerializer().serialize(topic, headers, event);
        AnalysisRequestedEvent read = consumerFactory.getValueDeserializer().deserialize(topic, headers, bytes);

        assertThat(read).isEqualTo(event);
        assertThat(headers.lastHeader("__TypeId__")).isNull();
    }

    @Test
    void malformedRecordBecomesASkippableErrorNotAnException() {
        RecordHeaders headers = new RecordHeaders();

        AnalysisRequestedEvent read = consumerFactory.getValueDeserializer()
                .deserialize(topic, headers, "not json".getBytes(StandardCharsets.UTF_8));

        assertThat(read).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }
}

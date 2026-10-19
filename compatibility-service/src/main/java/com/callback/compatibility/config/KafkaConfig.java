package com.callback.compatibility.config;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * analysis-requested is produced and consumed by this service: the request path queues an
 * analysis, and a listener runs the LLM pipeline at the pace Groq's rate limit allows.
 */
@Configuration
public class KafkaConfig {

    // Spelled out because the recoverer's own default suffix differs between Spring Kafka versions,
    // and with auto-create off a dead letter sent to a topic that doesn't exist blocks the consumer
    // thread for the producer's whole metadata wait.
    static final String DEAD_LETTER_SUFFIX = ".DLT";

    @Bean
    public NewTopic analysisRequestedTopic(@Value("${compatibility.kafka.topic}") String topic) {
        // The broker has auto-create disabled (docker-compose.yml), so this is what creates the topic.
        // Keyed by ownerEmail: one user's analyses stay in order, and 3 partitions leave room to add
        // consumers later without repartitioning.
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic analysisRequestedDeadLetterTopic(@Value("${compatibility.kafka.topic}") String topic) {
        // A dead-lettered record goes to the same partition number it came from, so this needs at
        // least as many partitions as the main topic.
        return TopicBuilder.name(topic + DEAD_LETTER_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, AnalysisRequestedEvent> analysisProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers, ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // send() itself can block for max.block.ms (60s by default) fetching metadata before it
        // even returns a future, so the request path's 5s wait on that future wouldn't cover it.
        // These keep a broker outage to a ~5s 503 instead of a minute-long hang.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);

        // Boot's ObjectMapper writes Instants as ISO-8601 strings. No __TypeId__ header: the
        // consumer binds to a fixed type and ignores it anyway.
        JsonSerializer<AnalysisRequestedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, AnalysisRequestedEvent> analysisKafkaTemplate(
            ProducerFactory<String, AnalysisRequestedEvent> analysisProducerFactory) {
        return new KafkaTemplate<>(analysisProducerFactory);
    }

    /**
     * Used only to publish dead letters. A record that failed to deserialize is republished as the
     * raw bytes it arrived as, so the value serializer has to take both bytes and events.
     */
    @Bean
    public KafkaTemplate<String, Object> analysisDeadLetterKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers, ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // The consumer thread waits on this send; fail in seconds, not the default minute, if the
        // broker or the dead-letter topic is unavailable (the error handler then retries the record).
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);

        JsonSerializer<AnalysisRequestedEvent> eventSerializer = new JsonSerializer<>(objectMapper);
        eventSerializer.setAddTypeInfo(false);
        Map<Class<?>, Serializer<?>> delegates = new HashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(AnalysisRequestedEvent.class, eventSerializer);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props, new StringSerializer(),
                new DelegatingByTypeSerializer(delegates)));
    }

    @Bean
    public ConsumerFactory<String, AnalysisRequestedEvent> analysisConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${compatibility.kafka.group-id}") String groupId) {
        // useHeadersIfPresent=false: always bind to AnalysisRequestedEvent, whatever a record's
        // headers claim. With a fixed target type there is no trusted-packages list to widen.
        JsonDeserializer<AnalysisRequestedEvent> json = new JsonDeserializer<>(AnalysisRequestedEvent.class, false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // One analysis takes ~1-2 minutes (Groq's TPM limit, with backoff). With the defaults
        // (500 records per poll, 5 minutes between polls) a few queued analyses would outlast the
        // poll interval, the broker would evict the consumer, and every one would be redelivered.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);

        // Wrapping the instance (instances passed here override props) turns malformed JSON into a
        // DeserializationException the error handler skips, instead of a poison pill that fails
        // every poll forever.
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AnalysisRequestedEvent> analysisListenerContainerFactory(
            ConsumerFactory<String, AnalysisRequestedEvent> analysisConsumerFactory,
            KafkaTemplate<String, Object> analysisDeadLetterKafkaTemplate) {
        var deadLetters = new DeadLetterPublishingRecoverer(analysisDeadLetterKafkaTemplate,
                (record, e) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, record.partition()));
        var factory = new ConcurrentKafkaListenerContainerFactory<String, AnalysisRequestedEvent>();
        factory.setConsumerFactory(analysisConsumerFactory);
        // The listener catches pipeline failures itself and marks the analysis FAILED, because a
        // redelivery would re-run the whole LLM pipeline against the TPM budget. Only what escapes
        // it (e.g. the database briefly down) reaches here: 2 quick retries, then the record is
        // published to <topic>.DLT so it can be inspected or replayed instead of vanishing from the
        // log. DeserializationException is never retried, only dead-lettered.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                deadLetters, new FixedBackOff(2_000, 2)));
        return factory;
    }
}

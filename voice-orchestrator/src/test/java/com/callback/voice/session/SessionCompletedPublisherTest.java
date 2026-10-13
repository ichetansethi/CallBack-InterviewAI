package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the producer side in isolation — no session-history-service involved: a finished session
 * is published to the real local broker (docker-compose's callback-kafka) and cleared from real
 * Redis, then the record is read back with a plain String consumer so the assertions are on the
 * actual bytes and headers on the wire, not on this service's own DTO.
 *
 * <p>Each run publishes to its own topic so it can never feed a running session-history-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionCompletedPublisherTest {

    private static final String TOPIC = "session-completed-vo-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("session-history.kafka.topic", () -> TOPIC);
    }

    @Autowired
    private SessionCompletedPublisher publisher;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private ReactiveRedisTemplate<String, InterviewSessionState> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static KafkaAdmin kafkaAdmin;

    @Autowired
    void captureAdmin(KafkaAdmin admin) {
        kafkaAdmin = admin;
    }

    @AfterAll
    static void deleteTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.deleteTopics(List.of(TOPIC)).all().get();
        }
    }

    @Test
    void publishesTheWireContractSessionHistoryServiceExpectsAndClearsRedis() throws Exception {
        UUID questionSetId = UUID.randomUUID();
        String owner = "publisher-" + UUID.randomUUID() + "@example.com";
        String redisSessionId = owner + "::" + questionSetId;
        InterviewSessionState finished = InterviewSessionState.newSession(redisSessionId, owner, List.of(
                        new InterviewQuestionDto("technical", "How do you size Kafka partitions?", "JD: Kafka at scale"),
                        new InterviewQuestionDto("behavioral", "Tell me about a conflict.", "Collaboration")))
                .withTurn(new TurnRecord("Hello", "follow_up", "How do you size Kafka partitions?"))
                .withTurn(new TurnRecord("By target throughput per consumer.", "advance", "Tell me about a conflict."))
                .withTurn(new TurnRecord("We disagreed on a schema.", "end", "Thanks, that's all."));
        sessionRepository.save(finished).block();
        assertThat(redisTemplate.hasKey("voice:session:" + redisSessionId).block()).isTrue();

        UUID publishedId = publisher.publishCompleted(finished, questionSetId).block(Duration.ofSeconds(20));

        ConsumerRecord<String, String> record = readRecords(r -> r.key().equals(publishedId.toString()), 1).getFirst();
        assertThat(record.key()).isEqualTo(publishedId.toString());
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();

        JsonNode json = objectMapper.readTree(record.value());
        assertThat(json.get("sessionId").asText()).isEqualTo(publishedId.toString());
        assertThat(json.get("ownerEmail").asText()).isEqualTo(owner);
        assertThat(json.get("questionSetId").asText()).isEqualTo(questionSetId.toString());
        // ISO-8601 strings, not epoch numbers.
        assertThat(json.get("startedAt").isTextual()).isTrue();
        assertThat(Instant.parse(json.get("startedAt").asText())).isEqualTo(finished.createdAt());
        assertThat(Instant.parse(json.get("endedAt").asText())).isEqualTo(finished.updatedAt());

        JsonNode transcript = json.get("transcript");
        assertThat(transcript).hasSize(6);
        JsonNode firstCoach = transcript.get(1);
        assertThat(firstCoach.get("speaker").asText()).isEqualTo("COACH");
        assertThat(firstCoach.get("text").asText()).isEqualTo("How do you size Kafka partitions?");
        assertThat(firstCoach.get("questionRationale").asText()).isEqualTo("JD: Kafka at scale");
        assertThat(firstCoach.get("turnIndex").asInt()).isEqualTo(1);
        assertThat(transcript.get(3).get("questionRationale").asText()).isEqualTo("Collaboration");
        assertThat(transcript.get(5).get("questionRationale").isNull()).isTrue(); // closing remark
        assertThat(transcript.get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("speaker", "text", "questionRationale", "turnIndex");

        assertThat(redisTemplate.hasKey("voice:session:" + redisSessionId).block()).isFalse();
    }

    @Test
    void rePublishingAnEndedRunReusesItsHistoryIdButANewRunGetsItsOwn() throws Exception {
        String owner = "publisher-" + UUID.randomUUID() + "@example.com";
        UUID questionSetId = UUID.randomUUID();
        String redisSessionId = owner + "::" + questionSetId;
        InterviewSessionState run = InterviewSessionState.newSession(redisSessionId, owner, List.of())
                .withTurn(new TurnRecord("hi", "end", "bye"));

        UUID first = publisher.publishCompleted(run, questionSetId).block(Duration.ofSeconds(20));
        // What Redis still holds if the broker acked but the delete then failed: the same run,
        // with the id the publisher saved before sending.
        InterviewSessionState leftInRedis = run.withCompletedSessionId(first);
        UUID retried = publisher.publishCompleted(leftInRedis, questionSetId).block(Duration.ofSeconds(20));
        UUID nextRun = publisher.publishCompleted(InterviewSessionState.newSession(redisSessionId, owner, List.of())
                .withTurn(new TurnRecord("again", "end", "bye")), questionSetId).block(Duration.ofSeconds(20));

        assertThat(retried).isEqualTo(first);
        assertThat(nextRun).isNotEqualTo(first);
        List<ConsumerRecord<String, String>> duplicates = readRecords(r -> r.key().equals(first.toString()), 2);
        assertThat(duplicates).hasSize(2);
        for (ConsumerRecord<String, String> record : duplicates) {
            assertThat(objectMapper.readTree(record.value()).get("sessionId").asText()).isEqualTo(first.toString());
        }
        assertThat(redisTemplate.hasKey("voice:session:" + redisSessionId).block()).isFalse();
    }

    /** Reads from the start of the topic until {@code count} records match, or fails after 15s. */
    private List<ConsumerRecord<String, String>> readRecords(Predicate<ConsumerRecord<String, String>> filter, int count) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAdmin.getConfigurationProperties().get("bootstrap.servers"),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<ConsumerRecord<String, String>> matched = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (filter.test(r)) {
                        matched.add(r);
                    }
                });
                if (matched.size() >= count) {
                    return matched;
                }
            }
        }
        throw new AssertionError("Fewer than " + count + " matching record(s) on " + TOPIC + " within 15s");
    }
}

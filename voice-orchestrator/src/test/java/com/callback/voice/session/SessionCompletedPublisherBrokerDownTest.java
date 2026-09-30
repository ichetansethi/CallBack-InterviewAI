package com.callback.voice.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With no broker reachable, publishing must fail promptly (bounded by KafkaProducerConfig's
 * max.block.ms, not Kafka's 60s default) and leave the transcript in Redis rather than losing it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.kafka.bootstrap-servers=localhost:1")
class SessionCompletedPublisherBrokerDownTest {

    @Autowired
    private SessionCompletedPublisher publisher;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private ReactiveRedisTemplate<String, InterviewSessionState> redisTemplate;

    @Test
    void failsWithinTheBoundedTimeoutAndKeepsTheTranscriptInRedis() {
        String owner = "brokerdown-" + UUID.randomUUID() + "@example.com";
        UUID questionSetId = UUID.randomUUID();
        String redisSessionId = owner + "::" + questionSetId;
        InterviewSessionState finished = InterviewSessionState.newSession(redisSessionId, owner, List.of())
                .withTurn(new TurnRecord("hi", "end", "bye"));
        sessionRepository.save(finished).block();

        long start = System.nanoTime();
        assertThatThrownBy(() -> publisher.publishCompleted(finished, questionSetId).block(Duration.ofSeconds(30)))
                .isNotNull();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(20));
        assertThat(redisTemplate.hasKey("voice:session:" + redisSessionId).block()).isTrue();
        sessionRepository.delete(redisSessionId).block();
    }
}

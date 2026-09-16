package com.callback.voice.session;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Repository
public class InterviewSessionRepository {

    private static final String KEY_PREFIX = "voice:session:";

    // Bounds how long an abandoned session lingers in Redis; refreshed on every save so an
    // active interview never expires mid-conversation.
    private static final Duration TTL = Duration.ofHours(2);

    private final ReactiveRedisTemplate<String, InterviewSessionState> redisTemplate;

    public InterviewSessionRepository(ReactiveRedisTemplate<String, InterviewSessionState> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<InterviewSessionState> loadOrCreate(String sessionId, String ownerEmail) {
        return redisTemplate.opsForValue().get(key(sessionId))
                .switchIfEmpty(Mono.defer(() -> save(InterviewSessionState.newSession(sessionId, ownerEmail))));
    }

    public Mono<InterviewSessionState> save(InterviewSessionState state) {
        return redisTemplate.opsForValue()
                .set(key(state.sessionId()), state, TTL)
                .thenReturn(state);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

}

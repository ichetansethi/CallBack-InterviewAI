package com.callback.voice.session;

import com.callback.voice.DTO.InterviewQuestionDto;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

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

    /**
     * {@code questions} is only used if no session exists yet at this key — on a resume, the
     * already-persisted question list and progress index win, even if the caller re-fetched a
     * (possibly different) question set from question-service. See InterviewSessionState's
     * javadoc for why the question list must not change mid-interview.
     */
    public Mono<InterviewSessionState> loadOrCreate(String sessionId, String ownerEmail,
                                                      List<InterviewQuestionDto> questions) {
        return redisTemplate.opsForValue().get(key(sessionId))
                .switchIfEmpty(Mono.defer(() -> save(InterviewSessionState.newSession(sessionId, ownerEmail, questions))));
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

package com.callback.voice.turn;

import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Decides whether to ask a follow-up or advance to the next prepared question, using function
 * calling: same single-tool-exchange discipline as compatibility-service's CompatibilityScorer and
 * question-service's QuestionGenerationService (bounded retries + a hard per-call timeout guarding
 * against tool-call loops). Duplicated rather than shared as a library, matching those services'
 * own precedent.
 *
 * <p>ChatClient.call() is a blocking exchange, so it's shifted onto boundedElastic — this class is
 * used from voice-orchestrator's reactive WebSocket pipeline and must never block a Netty event
 * loop thread.
 */
@Service
public class TurnDecisionService {

    private static final Logger log = LoggerFactory.getLogger(TurnDecisionService.class);
    private static final int MAX_ATTEMPTS = 4;

    /** See CompatibilityScorer.MODEL_CALL_TIMEOUT for why this exists — guards against a runaway
     * tool-call repetition loop hanging the exchange indefinitely. */
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "turn-decision-model-call");
        t.setDaemon(true);
        return t;
    });

    private final ChatClient chatClient;

    public TurnDecisionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public Mono<TurnDecision> decideNextTurn(String transcript, InterviewSessionState state) {
        return Mono.fromCallable(() -> decideNextTurnBlocking(transcript, state))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TurnDecision decideNextTurnBlocking(String transcript, InterviewSessionState state) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            TurnDecisionRecorder recorder = new TurnDecisionRecorder();
            try {
                callWithTimeout(() -> chatClient.prompt()
                        .system("""
                                You are conducting a mock interview. Decide whether to ask a follow-up
                                question based on the candidate's last answer, or advance to the next
                                prepared question. Respond only via a tool call, never in plain text:
                                call submitTurnDecision exactly once.""")
                        .user(buildTurnPrompt(transcript, state))
                        .tools(recorder)
                        .call()
                        .content());

                TurnDecision decision = recorder.result().orElseThrow(() ->
                        new IllegalStateException("Model did not call submitTurnDecision"));
                if (decision.responseText() == null || decision.responseText().isBlank()) {
                    throw new IllegalStateException("Model submitted a blank responseText");
                }
                return decision;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Turn decision attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoffBeforeRetry();
            }
        }
        throw new IllegalStateException("Model failed to produce a valid turn decision after "
                + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    private void backoffBeforeRetry() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> T callWithTimeout(Supplier<T> modelCall) {
        Future<T> future = MODEL_CALL_EXECUTOR.submit(modelCall::get);
        try {
            return future.get(MODEL_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "Model call exceeded " + MODEL_CALL_TIMEOUT.toSeconds() + "s (likely a runaway tool-call loop)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Model call failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for model call", e);
        }
    }

    static String buildTurnPrompt(String transcript, InterviewSessionState state) {
        String history = state.history().isEmpty()
                ? "(none yet — this is the candidate's first response)"
                : state.history().stream()
                        .map(t -> "Candidate: " + t.transcript() + "\nInterviewer: " + t.responseText())
                        .collect(Collectors.joining("\n---\n"));

        return """
                CONVERSATION SO FAR:
                %s

                CANDIDATE'S LATEST ANSWER (transcribed from speech):
                %s""".formatted(history, transcript);
    }

}

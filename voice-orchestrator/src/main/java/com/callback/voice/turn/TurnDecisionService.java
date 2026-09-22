package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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

    // The exchange number below which the interview must not end, and the one by which it should
    // have. These are told to the model explicitly in the prompt (see buildTurnPrompt) rather than
    // left as vague prose ("roughly 5-8 exchanges") for it to infer by counting the rendered
    // transcript itself — live testing showed the model never ended on its own without an explicit
    // number to compare against, only when the candidate gave it an explicit verbal cue instead.
    private static final int MIN_EXCHANGES_BEFORE_END = 6;
    private static final int TARGET_MAX_EXCHANGES = 8;

    /** See CompatibilityScorer.MODEL_CALL_TIMEOUT for why this exists — guards against a runaway
     * tool-call repetition loop hanging the exchange indefinitely. */
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "turn-decision-model-call");
        t.setDaemon(true);
        return t;
    });

    private final ChatClient chatClient;

    private static final Set<String> VALID_ACTIONS = Set.of("follow_up", "advance", "end");

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
                                question based on the candidate's last answer, advance to the next
                                prepared question shown to you in the user message, or end the interview
                                with a closing remark. The user message tells you the current exchange
                                number and the current prepared question — use those, don't count the
                                transcript yourself. Never end before exchange %d, even if you feel you
                                already have enough. From exchange %d onward, end with a closing remark
                                as soon as you have a well-rounded picture of the candidate rather than
                                continuing to probe. By exchange %d, end regardless, even if you'd like
                                to ask more. If the user message tells you this is the last prepared
                                question and you would otherwise advance, end instead — there is nothing
                                left to advance to, so give a closing remark, not another question.
                                Respond only via a tool call, never in plain text: call
                                submitTurnDecision exactly once.
                                """.formatted(MIN_EXCHANGES_BEFORE_END, MIN_EXCHANGES_BEFORE_END, TARGET_MAX_EXCHANGES))
                        .user(buildTurnPrompt(transcript, state))
                        .tools(recorder)
                        .call()
                        .content());

                TurnDecision decision = recorder.result().orElseThrow(() ->
                        new IllegalStateException("Model did not call submitTurnDecision"));
                if (decision.responseText() == null || decision.responseText().isBlank()) {
                    throw new IllegalStateException("Model submitted a blank responseText");
                }
                if (decision.action() == null || !VALID_ACTIONS.contains(decision.action().toLowerCase())) {
                    throw new IllegalStateException("Model submitted an unrecognized action: " + decision.action());
                }
                return decision;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Turn decision attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoffBeforeRetry(e);
            }
        }
        throw new IllegalStateException("Model failed to produce a valid turn decision after "
                + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    /** Backs off before a retry — longer if the failure looks like a rate limit, so the retry loop
     * doesn't just immediately re-hit the same limit within the same window. Same pattern as
     * CompatibilityScorer.backoffBeforeRetry in compatibility-service. */
    private void backoffBeforeRetry(RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        long millis = (message.contains("429") || message.toLowerCase().contains("rate_limit")) ? 12000 : 300;
        try {
            Thread.sleep(millis);
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
        // The decision being made now covers the exchange that will become turnCount+1 once this
        // turn is saved (state.turnCount() is how many turns are already committed, i.e. how many
        // happened before this one).
        int currentExchangeNumber = state.turnCount() + 1;

        String history = state.history().isEmpty()
                ? "(none yet — this is the candidate's first response)"
                : state.history().stream()
                        .map(t -> "Candidate: " + t.transcript() + "\nInterviewer: " + t.responseText())
                        .collect(Collectors.joining("\n---\n"));

        return """
                CURRENT EXCHANGE NUMBER: %d

                %s

                CONVERSATION SO FAR:
                %s

                CANDIDATE'S LATEST ANSWER (transcribed from speech):
                %s""".formatted(currentExchangeNumber, buildQuestionContext(state), history, transcript);
    }

    private static String buildQuestionContext(InterviewSessionState state) {
        List<InterviewQuestionDto> questions = state.questions();
        if (questions == null || questions.isEmpty()) {
            return "PREPARED QUESTIONS: none provided for this session — use your own judgment for "
                    + "what to ask, there is nothing to \"advance\" to.";
        }

        int index = state.currentQuestionIndex();
        InterviewQuestionDto current = questions.get(index);
        boolean isLastQuestion = index >= questions.size() - 1;

        String progressLine = "CURRENT PREPARED QUESTION (%d of %d) [%s]: %s"
                .formatted(index + 1, questions.size(), current.category(), current.questionText());

        String nextLine = isLastQuestion
                ? "This is the LAST prepared question. If you would advance, end the interview instead."
                : "If you advance, the next prepared question is [%s]: %s"
                        .formatted(questions.get(index + 1).category(), questions.get(index + 1).questionText());

        return progressLine + "\n" + nextLine;
    }

}

package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Every attempt for one turn shares a single time budget (maxTurnDuration, 20s by default): the
 * candidate is waiting in silence on a live connection, and a turn that stalled ~40s on Groq 429s
 * got the connection dropped (live run, 2026-09-30). A 429 waits out Groq's own Retry-After (or a
 * 2s/4s/8s fallback) only if that wait still fits the remaining budget; otherwise the turn fails
 * immediately and VoiceWebSocketHandler's "could you repeat that?" fallback keeps the interview
 * alive. A malformed tool call is a model mistake, not a rate limit, and gets the short 300ms
 * retry instead — same split as question-service's QuestionGenerationService.
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

    private static final Duration[] RATE_LIMIT_FALLBACK_DELAYS = {
            Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)
    };
    private static final Duration MODEL_MISTAKE_RETRY_DELAY = Duration.ofMillis(300);
    /** Don't start an attempt with less than this left — it couldn't complete a tool-call exchange anyway. */
    private static final Duration MIN_ATTEMPT_TIME = Duration.ofSeconds(2);

    private final ChatClient chatClient;
    private final Duration maxTurnDuration;

    private static final Set<String> VALID_ACTIONS = Set.of("follow_up", "advance", "end");

    public TurnDecisionService(ChatClient.Builder chatClientBuilder,
                               @Value("${turn-decision.max-turn-duration:20s}") Duration maxTurnDuration) {
        this.chatClient = chatClientBuilder.build();
        this.maxTurnDuration = maxTurnDuration;
    }

    public Mono<TurnDecision> decideNextTurn(String transcript, InterviewSessionState state) {
        return Mono.fromCallable(() -> decideNextTurnBlocking(transcript, state))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TurnDecision decideNextTurnBlocking(String transcript, InterviewSessionState state) {
        long deadline = System.nanoTime() + maxTurnDuration.toNanos();
        RuntimeException lastFailure = null;
        int rateLimitHits = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            TurnDecisionRecorder recorder = new TurnDecisionRecorder();
            try {
                callWithTimeout(remaining(deadline), () -> chatClient.prompt()
                        .system("""
                                You are conducting a mock interview. Decide whether to ask a follow-up
                                question based on the candidate's last answer, advance to the next
                                prepared question shown to you in the user message, or end the interview
                                with a closing remark. The user message tells you which prepared question
                                is being discussed, how many follow-ups it has already had, and exactly
                                which actions are allowed this turn — choose only from those. When there
                                are prepared questions, end once the last one has been discussed, or
                                earlier only if the candidate asks to stop. Respond only via a tool call,
                                never in plain text: call submitTurnDecision exactly once.
                                """)
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
                // The deterministic guard (follow-up cap, last-question end, safety cap): a
                // disallowed action is a model mistake and gets another attempt — never rewritten,
                // since responseText was written for the action the model chose.
                Set<String> allowed = TurnActionPolicy.allowedActions(state);
                if (!allowed.contains(decision.action().toLowerCase())) {
                    throw new IllegalStateException("Model chose '" + decision.action() + "' but only "
                            + allowed + " are allowed this turn");
                }
                decision = new TurnDecision(decision.action().toLowerCase(), decision.responseText());
                return decision;
            } catch (RuntimeException e) {
                lastFailure = e;
                GroqRateLimitException rateLimit = rateLimitCause(e);
                Duration delay = rateLimit != null
                        ? rateLimitDelay(rateLimit, rateLimitHits++)
                        : MODEL_MISTAKE_RETRY_DELAY;
                log.warn("Turn decision attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                if (remaining(deadline).minus(delay).compareTo(MIN_ATTEMPT_TIME) < 0) {
                    throw new IllegalStateException("Turn decision gave up after " + attempt + " attempt(s): a "
                            + delay.toMillis() + "ms " + (rateLimit != null ? "rate-limit " : "") + "wait would exceed the "
                            + maxTurnDuration.toSeconds() + "s turn budget", e);
                }
                sleep(delay);
            }
        }
        throw new IllegalStateException("Model failed to produce a valid turn decision after "
                + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    /** Groq's own Retry-After when it sent one — it knows its reset window better than a guess. */
    private static Duration rateLimitDelay(GroqRateLimitException e, int previousHits) {
        return e.retryAfter() != null
                ? e.retryAfter()
                : RATE_LIMIT_FALLBACK_DELAYS[Math.min(previousHits, RATE_LIMIT_FALLBACK_DELAYS.length - 1)];
    }

    /** The 429 surfaces from inside Spring AI's call, possibly wrapped — look through the cause chain. */
    private static GroqRateLimitException rateLimitCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof GroqRateLimitException r) {
                return r;
            }
        }
        return null;
    }

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during turn-decision retry backoff", e);
        }
    }

    /** Capped by both MODEL_CALL_TIMEOUT and whatever is left of the turn budget. */
    private <T> T callWithTimeout(Duration remainingBudget, Supplier<T> modelCall) {
        Duration timeout = remainingBudget.compareTo(MODEL_CALL_TIMEOUT) < 0 ? remainingBudget : MODEL_CALL_TIMEOUT;
        Future<T> future = MODEL_CALL_EXECUTOR.submit(modelCall::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Model call exceeded " + timeout.toMillis()
                    + "ms (turn budget, or a runaway tool-call loop)", e);
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
        int currentExchangeNumber = TurnActionPolicy.currentExchange(state);

        String history = state.history().isEmpty()
                ? "(none yet — this is the candidate's first response)"
                : state.history().stream()
                        .map(t -> "Candidate: " + t.transcript() + "\nInterviewer: " + t.responseText())
                        .collect(Collectors.joining("\n---\n"));

        return """
                CURRENT EXCHANGE NUMBER: %d

                %s

                %s

                CONVERSATION SO FAR:
                %s

                CANDIDATE'S LATEST ANSWER (transcribed from speech):
                %s""".formatted(currentExchangeNumber, buildQuestionContext(state), buildAllowedActionsLine(state), history, transcript);
    }

    private static String buildQuestionContext(InterviewSessionState state) {
        List<InterviewQuestionDto> questions = state.questions();
        if (questions == null || questions.isEmpty()) {
            return ("PREPARED QUESTIONS: none provided for this session — use your own judgment for what to ask, "
                    + "there is nothing to \"advance\" to. End with a closing remark once you have a well-rounded "
                    + "picture of the candidate, typically around exchange 6; the interview is stopped at exchange %d.")
                    .formatted(TurnActionPolicy.NO_QUESTIONS_MAX_EXCHANGES);
        }

        // Opening exchange: nothing has been asked yet. Labelling question 1 "current" here (and
        // offering question 2 as "next") is what made the model open with an "advance" that asked
        // question 1 while the cursor moved to 2 — see InterviewSessionState.nextQuestionIndex.
        if (state.turnCount() == 0) {
            InterviewQuestionDto first = questions.get(0);
            return ("OPENING EXCHANGE: no prepared question has been asked yet. Greet the candidate briefly and "
                    + "ask prepared question 1 of %d [%s]: %s")
                    .formatted(questions.size(), first.category(), first.questionText());
        }

        int index = state.currentQuestionIndex();
        InterviewQuestionDto current = questions.get(index);
        boolean isLastQuestion = index >= questions.size() - 1;

        String progressLine = "PREPARED QUESTION CURRENTLY BEING DISCUSSED — already asked (%d of %d) [%s]: %s"
                .formatted(index + 1, questions.size(), current.category(), current.questionText());

        String followUpLine = "FOLLOW-UPS ALREADY ASKED ON THIS QUESTION: %d of %d"
                .formatted(state.followUpCountForCurrentQuestion(), TurnActionPolicy.MAX_FOLLOW_UPS_PER_QUESTION);

        String nextLine = isLastQuestion
                ? "This is the LAST prepared question — there is nothing to advance to. When it has been "
                        + "discussed enough, end the interview with a closing remark."
                : "If you advance, ask the next prepared question [%s]: %s"
                        .formatted(questions.get(index + 1).category(), questions.get(index + 1).questionText());

        return progressLine + "\n" + followUpLine + "\n" + nextLine;
    }

    /** Rendered from the same TurnActionPolicy.allowedActions the validation enforces. */
    static String buildAllowedActionsLine(InterviewSessionState state) {
        String line = "ALLOWED ACTIONS THIS TURN: " + String.join(", ", TurnActionPolicy.allowedActions(state));
        if (TurnActionPolicy.safetyCapReached(state)) {
            return line + " — the interview has reached its maximum length; give a closing remark.";
        }
        if (state.turnCount() > 0 && state.questions() != null && !state.questions().isEmpty()
                && TurnActionPolicy.followUpLimitReached(state)) {
            return line + " — the follow-up limit for this question is reached; do not ask another follow-up.";
        }
        return line;
    }

}

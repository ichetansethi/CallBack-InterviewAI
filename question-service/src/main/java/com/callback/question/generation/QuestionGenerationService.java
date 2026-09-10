package com.callback.question.generation;

import com.callback.question.DTO.QuestionBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Generates interview questions from a job description using function calling: the model is
 * instructed to call submitQuestions rather than emit free text, so the batch comes back as
 * structured data instead of something parsed out of prose. Same reliability shape as
 * compatibility-service's CompatibilityScorer (bounded retries and a hard per-call timeout
 * guarding against tool-call loops) — not shared as a library yet, but the duplication is
 * deliberate for now, not an oversight.
 *
 * <p>Two independent failure modes get two independent retry mechanisms, deliberately not
 * conflated into one loop:
 * <ul>
 *   <li>A 429 from Groq (GroqRateLimitException, via ChatModelErrorHandlingConfig) is a provider
 *   rate limit, not a model mistake — retrying it needs to wait out the limit, not give the model
 *   another try. Handled by RATE_LIMIT_RETRY_TEMPLATE, scoped to that exception only, wrapping
 *   just the raw model call in callModel().</li>
 *   <li>A malformed/missing submitQuestions call (or an empty batch) IS a model mistake — retrying
 *   that means asking again, which is what the outer attempt loop in generate() is for. Applying
 *   rate-limit backoff to it would waste time without giving the model any reason to answer
 *   differently.</li>
 * </ul>
 */
@Service
public class QuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);
    private static final int MAX_ATTEMPTS = 4;

    private static final int TECHNICAL_COUNT = 3;
    private static final int BEHAVIORAL_COUNT = 2;
    private static final int ROLE_SPECIFIC_COUNT = 2;
    private static final int TOTAL_QUESTIONS = TECHNICAL_COUNT + BEHAVIORAL_COUNT + ROLE_SPECIFIC_COUNT;

    /**
     * See CompatibilityScorer.MODEL_CALL_TIMEOUT for why this exists. Sized generously enough to
     * comfortably fit RATE_LIMIT_RETRY_TEMPLATE's own worst case inside it (up to three backoffs —
     * 2s + 4s + 8s — plus up to four call attempts), while still catching a genuinely runaway
     * tool-call loop.
     */
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 1 initial attempt + up to 3 backoff retries (2s, 4s, 8s — or Groq's own Retry-After value,
     * when it sends one; see RateLimitBackOffPolicy) — scoped to GroqRateLimitException only, so a
     * malformed tool-call response never gets this treatment.
     */
    private static final RetryTemplate RATE_LIMIT_RETRY_TEMPLATE = RetryTemplate.builder()
            .maxAttempts(4)
            .retryOn(GroqRateLimitException.class)
            .customBackoff(new RateLimitBackOffPolicy())
            .build();

    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "question-generation-model-call");
        t.setDaemon(true);
        return t;
    });

    private final ChatClient chatClient;

    public QuestionGenerationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** Short, fixed pause before retrying a malformed/missing tool-call response — giving the
     * model another independent attempt, not waiting out a rate limit (that's callModel's job). */
    private void backoffBeforeRetry() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String callModel(Supplier<String> rawModelCall) {
        return RATE_LIMIT_RETRY_TEMPLATE.execute(context -> rawModelCall.get());
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

    /**
     * Generates a batch of interview questions from a job description, optionally tailored to
     * probe specific requirements a compatibility analysis found the candidate's resume doesn't
     * cover. Pass an empty list for uncoveredRequirements when no compatibility analysis was
     * requested.
     */
    public QuestionBatch generate(String jobDescriptionText, List<String> uncoveredRequirements) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            QuestionBatchRecorder recorder = new QuestionBatchRecorder();
            try {
                callWithTimeout(() -> callModel(() -> chatClient.prompt()
                        .system(buildSystemPrompt())
                        .user(buildUserPrompt(jobDescriptionText, uncoveredRequirements))
                        .tools(recorder)
                        .call()
                        .content()));

                QuestionBatch batch = recorder.result().orElseThrow(() ->
                        new IllegalStateException("Model did not call submitQuestions"));
                if (batch.questions().isEmpty()) {
                    throw new IllegalStateException("Model submitted an empty question batch");
                }
                return batch;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Question generation attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoffBeforeRetry();
            }
        }
        throw new IllegalStateException("Model failed to produce a valid question batch after "
                + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    /**
     * Package-private (not private) so QuestionGenerationServiceTest's raw, non-retried
     * nested-list stress test exercises this exact production prompt rather than a hand-duplicated
     * copy that could silently drift from it.
     */
    static String buildSystemPrompt() {
        return """
                You are an experienced technical interviewer. Generate exactly %d interview
                questions: %d technical, %d behavioral, and %d role-specific. Ground every question
                and its rationale in the job description text below — never invent requirements
                that aren't there. If JD requirements not covered by the candidate's resume are
                listed below, weight at least 1-2 of the questions to directly probe those specific
                gaps; the rest should cover the job description more broadly. Respond only via a
                tool call, never in plain text: call submitQuestions exactly once with the complete
                set.
                """.formatted(TOTAL_QUESTIONS, TECHNICAL_COUNT, BEHAVIORAL_COUNT, ROLE_SPECIFIC_COUNT);
    }

    static String buildUserPrompt(String jobDescriptionText, List<String> uncoveredRequirements) {
        return """
                JOB DESCRIPTION:
                %s

                JD REQUIREMENTS NOT COVERED BY THE CANDIDATE'S RESUME (probe at least 1-2 of these, if any):
                %s""".formatted(jobDescriptionText, formatUncoveredRequirements(uncoveredRequirements));
    }

    private static String formatUncoveredRequirements(List<String> uncoveredRequirements) {
        if (uncoveredRequirements.isEmpty()) {
            return "(none — no compatibility analysis was requested, or the resume covers everything)";
        }
        return uncoveredRequirements.stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}

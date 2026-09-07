package com.callback.compatibility.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Scores a resume against a job description using function calling: the model is instructed to
 * call recordJudgment/submitScores rather than emit free text, so the per-requirement gap
 * decisions and overall scores come back as structured data instead of something we have to parse
 * out of prose.
 *
 * <p>Two design decisions, both driven by observed local-model behavior rather than guesswork:
 * <ul>
 *   <li>Whether a requirement is met is judged by the model ONE REQUIREMENT AT A TIME, reading the
 *   actual evidence text — not by an embedding-similarity threshold. Cosine-similarity scores were
 *   found not to separate "resume covers this" from "resume doesn't" at all reliably: for one
 *   short resume, genuinely-covered requirements scored anywhere from ~0.49 to ~0.59, fully
 *   overlapping the range seen for genuinely-absent ones on the same resume. Judging requirements
 *   one at a time (rather than asking the model to sort gaps from met across a whole list in one
 *   exchange) was also more reliable — batched judgment was observed to sometimes flag a
 *   well-evidenced requirement as a gap and miss the actual gap.</li>
 *   <li>Suggestions and scores are separate model exchanges (one recordJudgment call per
 *   requirement, then one submitScores-only call), not one exchange handling everything.
 *   Reliability of a final tool call was observed to degrade sharply the more prior tool calls
 *   accumulated in the same exchange (malformed/missing arguments, or a degenerate repetition
 *   loop) — keeping each exchange to a single simple tool call avoided that in testing.</li>
 * </ul>
 */
@Service
public class CompatibilityScorer {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityScorer.class);
    private static final int MAX_ATTEMPTS = 4;

    /**
     * Hard wall-clock cap per model exchange. Observed in practice: a local 3B model can fall
     * into a degenerate tool-call repetition loop (repeatedly calling a tool with the same garbage
     * or duplicate arguments, sometimes never terminating) — Spring AI's internal tool-execution
     * loop has no built-in round cap to catch this. Without this timeout, a single request could
     * hang indefinitely instead of failing loudly and letting the retry loop take over.
     */
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "compatibility-scorer-model-call");
        t.setDaemon(true);
        return t;
    });

    private final ChatClient chatClient;

    public CompatibilityScorer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** Backs off before a retry — longer if the failure looks like a rate limit, so the retry loop
     * doesn't just immediately re-hit the same limit within the same window. */
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

    public ScoreBreakdown score(String jobDescriptionText, List<RequirementEvidence> requirementEvidence) {
        List<TweakSuggestion> suggestions = new ArrayList<>();
        for (RequirementEvidence re : requirementEvidence) {
            Judgment judgment = judgeRequirement(re);
            if (!judgment.supported()) {
                suggestions.add(new TweakSuggestion(re.requirement(), judgment.suggestion()));
            }
        }

        int[] scores = collectScores(jobDescriptionText, buildEvidenceBlock(requirementEvidence));

        return new ScoreBreakdown(scores[0], scores[1], scores[2], scores[3], List.copyOf(suggestions));
    }

    /**
     * A single judgment call was observed to be noisy run-to-run on the exact same input — even
     * missing the one obviously evidence-free requirement sometimes. Self-consistency (ask
     * independently several times, take the majority verdict) is a standard mitigation for this
     * kind of per-call LLM judgment noise, and meaningfully more stable in practice here than
     * trusting any single call.
     */
    private static final int JUDGMENT_VOTES = 3;

    private Judgment judgeRequirement(RequirementEvidence re) {
        List<Judgment> votes = new ArrayList<>();
        for (int i = 0; i < JUDGMENT_VOTES; i++) {
            judgeRequirementOnce(re).ifPresent(votes::add);
        }
        if (votes.isEmpty()) {
            log.warn("All {} judgment votes failed for requirement \"{}\"; defaulting to supported=true",
                    JUDGMENT_VOTES, re.requirement());
            return new Judgment(true, "");
        }

        long supportedVotes = votes.stream().filter(Judgment::supported).count();
        boolean majoritySupported = supportedVotes * 2 >= votes.size();
        log.info("Judgment votes for \"{}\": {}/{} supported -> {}",
                re.requirement(), supportedVotes, votes.size(), majoritySupported ? "MET" : "GAP");

        if (majoritySupported) {
            return new Judgment(true, "");
        }
        String suggestion = votes.stream()
                .filter(v -> !v.supported() && !v.suggestion().isBlank())
                .map(Judgment::suggestion)
                .findFirst()
                .orElse("Add resume content that specifically addresses: " + re.requirement());
        return new Judgment(false, suggestion);
    }

    private Optional<Judgment> judgeRequirementOnce(RequirementEvidence re) {
        String evidenceText = re.evidenceChunks().isEmpty()
                ? "(none — retrieval found nothing relevant in the resume)"
                : String.join(" | ", re.evidenceChunks());

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            JudgmentRecorder recorder = new JudgmentRecorder();
            try {
                callWithTimeout(() -> chatClient.prompt()
                        .system("""
                                You are a strict technical recruiter assistant. You are given ONE job requirement
                                and the resume evidence retrieval found for it. Judge only from the evidence text
                                shown — ignore any similarity score, it is not reliable. Respond only via a tool
                                call, never in plain text: call recordJudgment exactly once.""")
                        .user("""
                                JOB REQUIREMENT: %s

                                RESUME EVIDENCE RETRIEVED FOR THIS REQUIREMENT:
                                %s""".formatted(re.requirement(), evidenceText))
                        .tools(recorder)
                        .call()
                        .content());

                Judgment judgment = recorder.result().orElseThrow(() ->
                        new IllegalStateException("Model did not call recordJudgment"));
                if (!judgment.supported() && judgment.suggestion().isBlank()) {
                    // Contradicts the tool's own contract (supported=false requires a suggestion) —
                    // treat as a failed attempt rather than surface a useless empty suggestion.
                    throw new IllegalStateException("Model reported unsupported with a blank suggestion");
                }
                return Optional.of(judgment);
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Judgment attempt {}/{} for requirement \"{}\" failed: {}",
                        attempt, MAX_ATTEMPTS, re.requirement(), e.toString());
                backoffBeforeRetry(e);
            }
        }
        log.warn("Judgment vote failed for requirement \"{}\" after {} attempts: {}",
                re.requirement(), MAX_ATTEMPTS, lastFailure == null ? "unknown" : lastFailure.toString());
        return Optional.empty();
    }

    private int[] collectScores(String jdText, String evidenceBlock) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ScoreOnlyRecorder recorder = new ScoreOnlyRecorder();
            try {
                callWithTimeout(() -> chatClient.prompt()
                        .system("""
                                You are a strict technical recruiter assistant. You are given a job description and,
                                for each requirement extracted from it, the resume evidence retrieval found for that
                                specific requirement (or none, if retrieval found nothing relevant). Respond only via
                                a tool call, never in plain text: call submitScores exactly once with integer scores
                                0-100 for skillsOverlap, experienceMatch, keywordCoverage, and semanticSimilarity,
                                reflecting how well the evidence overall supports the job description.""")
                        .user("""
                                JOB DESCRIPTION:
                                %s

                                PER-REQUIREMENT EVIDENCE:
                                %s""".formatted(jdText, evidenceBlock))
                        .tools(recorder)
                        .call()
                        .content());

                return recorder.scores().orElseThrow(() ->
                        new IllegalStateException("Model did not call submitScores"));
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Scoring attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoffBeforeRetry(e);
            }
        }
        throw new IllegalStateException("Model failed to produce valid scores after "
                + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    private String buildEvidenceBlock(List<RequirementEvidence> requirementEvidence) {
        return requirementEvidence.stream()
                .map(re -> "Requirement: " + re.requirement() + "\nEvidence found: "
                        + (re.evidenceChunks().isEmpty() ? "(none)" : String.join(" | ", re.evidenceChunks())))
                .collect(Collectors.joining("\n---\n"));
    }
}

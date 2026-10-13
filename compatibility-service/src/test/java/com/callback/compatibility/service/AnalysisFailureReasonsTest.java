package com.callback.compatibility.service;

import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeoutException;

import static com.callback.compatibility.service.AnalysisStageException.Stage.AI_MODEL;
import static com.callback.compatibility.service.AnalysisStageException.Stage.RESUME_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each dependency failure gets a reason naming it. The exceptions are shaped like the ones seen
 * live on 2026-10-13, wrapped the way the extractor and scorer wrap them (the scorer's "all votes
 * failed" and "after 4 attempts" errors keep the last attempt's failure as their cause).
 */
class AnalysisFailureReasonsTest {

    private static final NonTransientAiException GROQ_401 = new NonTransientAiException(
            "HTTP 401 - {\"error\":{\"message\":\"Invalid API Key\",\"type\":\"invalid_request_error\","
                    + "\"code\":\"invalid_api_key\"}}");
    private static final NonTransientAiException GROQ_TPM = new NonTransientAiException(
            "HTTP 429 - {\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-20b` service tier "
                    + "`on_demand` on tokens per minute (TPM): Limit 8000, Used 7563, Requested 945.\"}}");
    private static final NonTransientAiException GROQ_500 = new NonTransientAiException(
            "HTTP 500 - {\"error\":{\"message\":\"Internal Server Error\"}}");
    private static final ResourceAccessException OLLAMA_DOWN = new ResourceAccessException(
            "I/O error on POST request for \"http://localhost:11434/api/embed\": null");

    @Test
    void aSpentDailyQuotaNamesTheAiProvider() {
        assertThat(AnalysisFailureReasons.of(new GroqQuotaExhaustedException(GROQ_TPM)))
                .isEqualTo(AnalysisFailureReasons.AI_DAILY_QUOTA);
    }

    @Test
    void anUnreachableEmbeddingServiceIsNamed() {
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(RESUME_SEARCH, OLLAMA_DOWN)))
                .isEqualTo(AnalysisFailureReasons.EMBEDDINGS_UNAVAILABLE);
    }

    @Test
    void anUnreachableVectorDatabaseIsNamed() {
        RuntimeException dbDown = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(RESUME_SEARCH, dbDown)))
                .isEqualTo(AnalysisFailureReasons.VECTOR_DB_UNAVAILABLE);
    }

    @Test
    void aRejectedApiKeyIsNamedEvenWhenEveryVoteFailedOnIt() {
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, GROQ_401)))
                .isEqualTo(AnalysisFailureReasons.AI_KEY_REJECTED);
        RuntimeException allVotesFailed = new IllegalStateException(
                "All 3 judgment votes failed for requirement \"Kafka\"", GROQ_401);
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, allVotesFailed)))
                .isEqualTo(AnalysisFailureReasons.AI_KEY_REJECTED);
    }

    @Test
    void aPerMinuteRateLimitThatOutlastedTheRetriesIsNamed() {
        RuntimeException scoringFailed = new IllegalStateException(
                "Model failed to produce valid scores after 4 attempts", GROQ_TPM);
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, scoringFailed)))
                .isEqualTo(AnalysisFailureReasons.AI_RATE_LIMITED);
    }

    @Test
    void aProviderErrorOrTimeoutIsNamed() {
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, GROQ_500)))
                .isEqualTo(AnalysisFailureReasons.AI_UNAVAILABLE);
        RuntimeException timedOut = new IllegalStateException("Model call exceeded 30s", new TimeoutException());
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, timedOut)))
                .isEqualTo(AnalysisFailureReasons.AI_UNAVAILABLE);
        RuntimeException unreachable = new ResourceAccessException("I/O error on POST request for "
                + "\"https://api.groq.com/openai/v1/chat/completions\": Connection refused");
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, unreachable)))
                .isEqualTo(AnalysisFailureReasons.AI_UNAVAILABLE);
    }

    @Test
    void anAnswerThatIsNotWhatWasAskedForBlamesTheModelNotTheProvider() {
        RuntimeException prose = new IllegalStateException("All 3 judgment votes failed for requirement \"Kafka\"",
                new IllegalStateException("Model did not call recordJudgment"));
        assertThat(AnalysisFailureReasons.of(new AnalysisStageException(AI_MODEL, prose)))
                .isEqualTo(AnalysisFailureReasons.AI_UNUSABLE_OUTPUT);
    }

    @Test
    void everyReasonFitsTheColumn() {
        for (String reason : new String[] {AnalysisFailureReasons.GENERIC, AnalysisFailureReasons.AI_DAILY_QUOTA,
                AnalysisFailureReasons.AI_KEY_REJECTED, AnalysisFailureReasons.AI_RATE_LIMITED,
                AnalysisFailureReasons.AI_UNAVAILABLE, AnalysisFailureReasons.AI_UNUSABLE_OUTPUT,
                AnalysisFailureReasons.EMBEDDINGS_UNAVAILABLE, AnalysisFailureReasons.VECTOR_DB_UNAVAILABLE}) {
            assertThat(reason).hasSizeLessThanOrEqualTo(255);
        }
    }
}

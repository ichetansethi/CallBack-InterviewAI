package com.callback.compatibility.service;

import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The failureReason a FAILED analysis is saved with, which clients see on GET /compatibility/{id}.
 * Each reason names the dependency that broke, by role rather than by product, and says whether
 * resubmitting can help: a rejected API key needs an operator, a dependency that was down does not.
 * The full exception is logged by the listener; these stay short (the column is 255 characters).
 */
final class AnalysisFailureReasons {

    static final String GENERIC = "Analysis could not be completed";
    static final String AI_DAILY_QUOTA = "The AI provider's daily quota is used up; submit the analysis again later";
    static final String AI_KEY_REJECTED = "The AI provider rejected this service's API key";
    static final String AI_RATE_LIMITED = "The AI provider's per-minute rate limit was still hit after retrying; submit the analysis again later";
    static final String AI_UNAVAILABLE = "The AI provider returned an error or did not respond in time; submit the analysis again later";
    static final String AI_UNUSABLE_OUTPUT = "The AI model did not return a usable result; submit the analysis again";
    static final String EMBEDDINGS_UNAVAILABLE = "The embedding service returned an error or could not be reached; submit the analysis again later";
    static final String VECTOR_DB_UNAVAILABLE = "The vector database returned an error or could not be reached; submit the analysis again later";

    /** Spring AI's error handler words HTTP failures as "HTTP 401 - {body}". */
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");

    private AnalysisFailureReasons() {
    }

    static String of(Throwable failure) {
        if (failure instanceof GroqQuotaExhaustedException || has(failure, GroqQuotaExhaustedException.class)) {
            return AI_DAILY_QUOTA;
        }
        if (!(failure instanceof AnalysisStageException staged)) {
            return GENERIC; // not from a dependency call, most likely a bug
        }
        return switch (staged.stage()) {
            case RESUME_SEARCH -> has(failure, DataAccessException.class) || has(failure, SQLException.class)
                    ? VECTOR_DB_UNAVAILABLE
                    : EMBEDDINGS_UNAVAILABLE;
            case AI_MODEL -> aiModelReason(failure);
        };
    }

    private static String aiModelReason(Throwable failure) {
        Integer status = httpStatus(failure);
        if (status != null && (status == 401 || status == 403)) {
            return AI_KEY_REJECTED;
        }
        if (status != null && status == 429) {
            return AI_RATE_LIMITED;
        }
        if (status != null || has(failure, NonTransientAiException.class) || has(failure, TransientAiException.class)
                || has(failure, ResourceAccessException.class) || has(failure, TimeoutException.class)) {
            return AI_UNAVAILABLE;
        }
        // The provider answered, but not with what was asked for: prose instead of a tool call,
        // unparseable JSON, a judgment that breaks the tool's contract.
        return AI_UNUSABLE_OUTPUT;
    }

    private static Integer httpStatus(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof NonTransientAiException || t instanceof TransientAiException) {
                Matcher m = HTTP_STATUS.matcher(String.valueOf(t.getMessage()));
                if (m.find()) {
                    return Integer.valueOf(m.group(1));
                }
            }
        }
        return null;
    }

    private static boolean has(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

package com.callback.question.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The compatibility analysis exists but has no result to tailor questions to: still PENDING or
 * IN_PROGRESS (retry once it completes), or FAILED (the caller must submit a new analysis).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AnalysisNotReadyException extends RuntimeException {
    public AnalysisNotReadyException(String message) {
        super(message);
    }
}

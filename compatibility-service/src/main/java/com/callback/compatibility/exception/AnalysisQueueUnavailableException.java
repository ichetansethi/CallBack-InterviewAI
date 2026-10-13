package com.callback.compatibility.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The analysis row was saved but its event couldn't be published, so it was marked FAILED. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AnalysisQueueUnavailableException extends RuntimeException {
    public AnalysisQueueUnavailableException(Throwable cause) {
        super("Could not queue analysis", cause);
    }
}

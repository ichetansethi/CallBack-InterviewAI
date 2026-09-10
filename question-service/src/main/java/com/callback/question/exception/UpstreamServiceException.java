package com.callback.question.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Any upstream failure (jd-resume-service, compatibility-service, ...) that isn't cleanly a 404 or 401. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class UpstreamServiceException extends RuntimeException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

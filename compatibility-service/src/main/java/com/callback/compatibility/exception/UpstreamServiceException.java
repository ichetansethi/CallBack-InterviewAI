package com.callback.compatibility.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Any upstream jd-resume-service failure that isn't cleanly a 404 or 401. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class UpstreamServiceException extends RuntimeException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

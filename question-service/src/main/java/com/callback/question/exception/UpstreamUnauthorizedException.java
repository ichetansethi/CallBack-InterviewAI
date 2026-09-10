package com.callback.question.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UpstreamUnauthorizedException extends RuntimeException {
    public UpstreamUnauthorizedException(String message) {
        super(message);
    }
}

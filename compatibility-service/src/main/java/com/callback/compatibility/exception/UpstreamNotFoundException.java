package com.callback.compatibility.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UpstreamNotFoundException extends RuntimeException {
    public UpstreamNotFoundException(String message) {
        super(message);
    }
}

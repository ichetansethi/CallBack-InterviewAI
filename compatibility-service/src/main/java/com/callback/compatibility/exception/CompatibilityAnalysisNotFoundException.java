package com.callback.compatibility.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CompatibilityAnalysisNotFoundException extends RuntimeException {
    public CompatibilityAnalysisNotFoundException(UUID id) {
        super("Compatibility analysis not found: " + id);
    }
}

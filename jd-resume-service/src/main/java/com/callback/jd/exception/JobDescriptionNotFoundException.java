package com.callback.jd.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class JobDescriptionNotFoundException extends RuntimeException {
    public JobDescriptionNotFoundException(UUID id) {
        super("Job description not found: " + id);
    }
}
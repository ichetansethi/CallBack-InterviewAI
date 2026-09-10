package com.callback.question.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class QuestionSetNotFoundException extends RuntimeException {
    public QuestionSetNotFoundException(UUID id) {
        super("Question set not found: " + id);
    }
}

package com.callback.question.controller;

import com.callback.question.DTO.QuestionGenerateRequest;
import com.callback.question.DTO.QuestionSetResponse;
import com.callback.question.service.QuestionSetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/questions")
public class QuestionSetController {

    private final QuestionSetService service;

    public QuestionSetController(QuestionSetService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public ResponseEntity<QuestionSetResponse> generate(
            @Valid @RequestBody QuestionGenerateRequest request,
            @RequestHeader("Authorization") String bearerToken) {
        String ownerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.generate(request, ownerEmail, bearerToken));
    }

    @GetMapping("/{id}")
    public QuestionSetResponse get(@PathVariable UUID id) {
        return service.getById(id, currentEmail());
    }

    @GetMapping
    public List<QuestionSetResponse> list() {
        return service.listForUser(currentEmail());
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

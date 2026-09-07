package com.callback.jd.controller;

import com.callback.jd.DTO.JobDescriptionRequest;
import com.callback.jd.DTO.JobDescriptionResponse;
import com.callback.jd.service.JobDescriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/jd")
public class JobDescriptionController {

    private final JobDescriptionService service;

    public JobDescriptionController(JobDescriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JobDescriptionResponse> create(@Valid @RequestBody JobDescriptionRequest request) {
        String ownerEmail = currentEmail();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, ownerEmail));
    }

    @GetMapping("/{id}")
    public JobDescriptionResponse get(@PathVariable("id") UUID id) {
        return service.getById(id, currentEmail());
    }

    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getText(@PathVariable("id") UUID id) {
        return service.getRawText(id, currentEmail());
    }

    @GetMapping
    public List<JobDescriptionResponse> list() {
        return service.listForUser(currentEmail());
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
package com.callback.jd.controller;

import com.callback.jd.DTO.ResumeResponse;
import com.callback.jd.service.ResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/resumes")
public class ResumeController {

    private final ResumeService service;

    public ResumeController(ResumeService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeResponse> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(file, currentEmail()));
    }

    @GetMapping("/{id}")
    public ResumeResponse get(@PathVariable("id") UUID id) {
        return service.getMetadata(id, currentEmail());
    }

    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getText(@PathVariable("id") UUID id) {
        return service.getExtractedText(id, currentEmail());
    }

    @GetMapping
    public List<ResumeResponse> list() {
        return service.listForUser(currentEmail());
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

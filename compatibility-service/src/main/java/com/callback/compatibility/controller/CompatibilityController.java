package com.callback.compatibility.controller;

import com.callback.compatibility.DTO.AnalyzeRequest;
import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.service.CompatibilityAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/compatibility")
public class CompatibilityController {

    private final CompatibilityAnalysisService service;

    public CompatibilityController(CompatibilityAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request,
                                                     HttpServletRequest httpRequest) {
        String authorizationHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        AnalyzeResponse response = service.analyze(request.jdId(), request.resumeId(), currentEmail(), authorizationHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public AnalyzeResponse get(@PathVariable("id") UUID id) {
        return service.getById(id, currentEmail());
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

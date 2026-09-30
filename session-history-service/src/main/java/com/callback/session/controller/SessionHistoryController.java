package com.callback.session.controller;

import com.callback.session.DTO.SessionResponse;
import com.callback.session.DTO.SessionSummaryResponse;
import com.callback.session.service.SessionHistoryService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/sessions")
public class SessionHistoryController {

    private final SessionHistoryService service;

    public SessionHistoryController(SessionHistoryService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable("id") UUID id) {
        return service.getById(id, currentEmail());
    }

    @GetMapping
    public List<SessionSummaryResponse> list() {
        return service.listForUser(currentEmail());
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

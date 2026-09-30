package com.callback.session.repository;

import com.callback.session.model.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    List<InterviewSession> findByOwnerEmailOrderByStartedAtDesc(String ownerEmail);
}

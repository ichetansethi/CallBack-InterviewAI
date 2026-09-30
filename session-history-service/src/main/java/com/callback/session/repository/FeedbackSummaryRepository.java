package com.callback.session.repository;

import com.callback.session.model.FeedbackSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FeedbackSummaryRepository extends JpaRepository<FeedbackSummary, UUID> {
    Optional<FeedbackSummary> findBySessionId(UUID sessionId);

    boolean existsBySessionId(UUID sessionId);
}

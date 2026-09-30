package com.callback.session.repository;

import com.callback.session.model.TranscriptTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TranscriptTurnRepository extends JpaRepository<TranscriptTurn, UUID> {
    List<TranscriptTurn> findBySessionIdOrderByTurnIndexAsc(UUID sessionId);
}

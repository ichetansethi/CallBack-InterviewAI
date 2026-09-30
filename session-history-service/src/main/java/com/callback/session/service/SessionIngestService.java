package com.callback.session.service;

import com.callback.session.DTO.SessionCompletedEvent;
import com.callback.session.DTO.TranscriptTurnDto;
import com.callback.session.model.InterviewSession;
import com.callback.session.model.SessionStatus;
import com.callback.session.model.Speaker;
import com.callback.session.model.TranscriptTurn;
import com.callback.session.repository.InterviewSessionRepository;
import com.callback.session.repository.TranscriptTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists a completed session and its transcript in one transaction, idempotently on sessionId:
 * a redelivered or retried event finds the session already there and writes nothing, so turns are
 * never duplicated. Kept separate from FeedbackService so the (slow, fallible) model call never
 * runs inside this transaction.
 */
@Service
public class SessionIngestService {

    private static final Logger log = LoggerFactory.getLogger(SessionIngestService.class);

    private final InterviewSessionRepository sessionRepository;
    private final TranscriptTurnRepository turnRepository;

    public SessionIngestService(InterviewSessionRepository sessionRepository, TranscriptTurnRepository turnRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
    }

    /** Throws IllegalArgumentException for an event that can never be stored (non-retryable — see KafkaConsumerConfig). */
    @Transactional
    public InterviewSession recordCompletedSession(SessionCompletedEvent event) {
        if (event.sessionId() == null || event.ownerEmail() == null || event.ownerEmail().isBlank()) {
            throw new IllegalArgumentException("session-completed event missing sessionId/ownerEmail: " + event);
        }
        var existing = sessionRepository.findById(event.sessionId());
        if (existing.isPresent()) {
            log.info("Session {} already recorded, skipping transcript insert", event.sessionId());
            return existing.get();
        }

        InterviewSession session = sessionRepository.save(new InterviewSession(
                event.sessionId(), event.ownerEmail(), event.questionSetId(),
                SessionStatus.COMPLETED, event.startedAt(), event.endedAt()
        ));

        List<TranscriptTurn> turns = transcriptOf(event).stream()
                .map(dto -> new TranscriptTurn(session, parseSpeaker(dto.speaker()), dto.text(),
                        dto.questionRationale(), dto.turnIndex()))
                .toList();
        turnRepository.saveAll(turns);
        return session;
    }

    public static List<TranscriptTurnDto> transcriptOf(SessionCompletedEvent event) {
        return event.transcript() == null ? List.of() : event.transcript();
    }

    private static Speaker parseSpeaker(String speaker) {
        try {
            return Speaker.valueOf(speaker);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown speaker '" + speaker + "', expected one of CANDIDATE/COACH", e);
        }
    }
}

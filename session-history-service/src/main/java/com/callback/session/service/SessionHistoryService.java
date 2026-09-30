package com.callback.session.service;

import com.callback.session.DTO.FeedbackResponse;
import com.callback.session.DTO.QuestionFeedbackResponse;
import com.callback.session.DTO.SessionResponse;
import com.callback.session.DTO.SessionSummaryResponse;
import com.callback.session.DTO.TranscriptTurnResponse;
import com.callback.session.exception.SessionNotFoundException;
import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.InterviewSession;
import com.callback.session.repository.FeedbackSummaryRepository;
import com.callback.session.repository.InterviewSessionRepository;
import com.callback.session.repository.TranscriptTurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read side of session history. Writes arrive via Kafka (SessionCompletedListener →
 * SessionIngestService, then FeedbackService); nothing here writes.
 */
@Service
public class SessionHistoryService {

    private final InterviewSessionRepository sessionRepository;
    private final TranscriptTurnRepository turnRepository;
    private final FeedbackSummaryRepository feedbackRepository;

    public SessionHistoryService(InterviewSessionRepository sessionRepository, TranscriptTurnRepository turnRepository,
                                 FeedbackSummaryRepository feedbackRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.feedbackRepository = feedbackRepository;
    }

    /** Read-only transaction so FeedbackSummary's lazy perQuestion list can load (open-in-view is off). */
    @Transactional(readOnly = true)
    public SessionResponse getById(UUID id, String callerEmail) {
        InterviewSession session = findOwned(id, callerEmail);
        List<TranscriptTurnResponse> transcript = turnRepository.findBySessionIdOrderByTurnIndexAsc(session.getId()).stream()
                .map(t -> new TranscriptTurnResponse(t.getTurnIndex(), t.getSpeaker(), t.getText(), t.getQuestionRationale()))
                .toList();
        FeedbackResponse feedback = feedbackRepository.findBySessionId(session.getId())
                .map(SessionHistoryService::toFeedbackResponse)
                .orElse(null);
        return new SessionResponse(session.getId(), session.getQuestionSetId(), session.getStatus(),
                session.getStartedAt(), session.getEndedAt(), transcript, feedback);
    }

    private static FeedbackResponse toFeedbackResponse(FeedbackSummary fb) {
        List<QuestionFeedbackResponse> perQuestion = fb.getPerQuestion().stream()
                .map(q -> new QuestionFeedbackResponse(q.getQuestionText(), q.getFeedbackText()))
                .toList();
        return new FeedbackResponse(fb.getOverallSummary(), fb.getClarityScore(), fb.getStructureScore(),
                fb.getTechnicalDepthScore(), fb.getCreatedAt(), perQuestion);
    }

    public List<SessionSummaryResponse> listForUser(String callerEmail) {
        return sessionRepository.findByOwnerEmailOrderByStartedAtDesc(callerEmail).stream()
                .map(s -> new SessionSummaryResponse(s.getId(), s.getQuestionSetId(), s.getStatus(),
                        s.getStartedAt(), s.getEndedAt()))
                .toList();
    }

    private InterviewSession findOwned(UUID id, String callerEmail) {
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));

        if (!session.getOwnerEmail().equals(callerEmail)) {
            // deliberately the SAME exception as "doesn't exist" — mirrors
            // QuestionSetService.findOwned, to avoid leaking existence of another user's session.
            throw new SessionNotFoundException(id);
        }

        return session;
    }
}

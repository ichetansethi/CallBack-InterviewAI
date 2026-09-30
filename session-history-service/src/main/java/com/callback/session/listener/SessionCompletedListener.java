package com.callback.session.listener;

import com.callback.session.DTO.SessionCompletedEvent;
import com.callback.session.model.InterviewSession;
import com.callback.session.service.FeedbackService;
import com.callback.session.service.SessionIngestService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Two idempotent steps, deliberately not one transaction: record the session + transcript, then
 * generate feedback. If feedback fails (e.g. a Groq 429), the error handler re-runs the whole
 * event; step one is then a no-op and only the feedback step is actually retried.
 */
@Component
public class SessionCompletedListener {

    private final SessionIngestService ingestService;
    private final FeedbackService feedbackService;

    public SessionCompletedListener(SessionIngestService ingestService, FeedbackService feedbackService) {
        this.ingestService = ingestService;
        this.feedbackService = feedbackService;
    }

    @KafkaListener(topics = "${session-history.kafka.topic}", containerFactory = "kafkaListenerContainerFactory")
    public void handle(SessionCompletedEvent event) {
        InterviewSession session = ingestService.recordCompletedSession(event);
        feedbackService.generateAndPersist(session, SessionIngestService.transcriptOf(event));
    }
}

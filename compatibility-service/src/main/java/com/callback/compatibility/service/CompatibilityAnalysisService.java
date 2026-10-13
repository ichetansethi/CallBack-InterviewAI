package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisAcceptedResponse;
import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.DTO.SuggestionResponse;
import com.callback.compatibility.client.JdResumeServiceClient;
import com.callback.compatibility.exception.AnalysisQueueUnavailableException;
import com.callback.compatibility.exception.CompatibilityAnalysisNotFoundException;
import com.callback.compatibility.model.AnalysisStatus;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Request side of an analysis: fetch JD + resume text from jd-resume-service (relaying the
 * caller's token, so jd-resume-service's own ownership checks are what actually gate access —
 * this service never re-implements that check), save a PENDING row and queue it on Kafka. The
 * analysis itself runs later, off the request thread. Also serves reads of the result.
 */
@Service
public class CompatibilityAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityAnalysisService.class);
    private static final int PUBLISH_TIMEOUT_SECONDS = 5;

    private final JdResumeServiceClient jdResumeServiceClient;
    private final CompatibilityAnalysisRepository repository;
    private final KafkaTemplate<String, AnalysisRequestedEvent> kafkaTemplate;
    private final String topic;

    public CompatibilityAnalysisService(JdResumeServiceClient jdResumeServiceClient,
                                         CompatibilityAnalysisRepository repository,
                                         KafkaTemplate<String, AnalysisRequestedEvent> kafkaTemplate,
                                         @Value("${compatibility.kafka.topic}") String topic) {
        this.jdResumeServiceClient = jdResumeServiceClient;
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Fetches both texts here, while the caller's token can still be relayed: a jdId or resumeId the
     * caller doesn't own is a 404 now, with no row saved and nothing queued. The event carries the
     * texts because the consumer has no token to fetch them with.
     *
     * Saving the row and then publishing is a dual write. If the publish fails, the row is marked
     * FAILED rather than left PENDING forever. (The production answer is a transactional outbox.)
     */
    public AnalysisAcceptedResponse requestAnalysis(UUID jdId, UUID resumeId, String ownerEmail, String authorizationHeader) {
        String jdText = jdResumeServiceClient.fetchJobDescriptionText(jdId, authorizationHeader);
        String resumeText = jdResumeServiceClient.fetchResumeText(resumeId, authorizationHeader);

        CompatibilityAnalysis analysis = repository.save(CompatibilityAnalysis.pending(ownerEmail, jdId, resumeId));
        AnalysisRequestedEvent event = new AnalysisRequestedEvent(
                analysis.getId(), ownerEmail, jdId, resumeId, jdText, resumeText, Instant.now());
        try {
            kafkaTemplate.send(topic, ownerEmail, event).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Could not publish analysis {}; marking it FAILED", analysis.getId(), e);
            analysis.markFailed("Could not queue analysis");
            repository.save(analysis);
            throw new AnalysisQueueUnavailableException(e);
        }
        return new AnalysisAcceptedResponse(analysis.getId(), analysis.getStatus());
    }

    // Keeps the session open while toResponse reads the lazy suggestions collection; with
    // open-in-view off, findById's session is otherwise already closed by then.
    @Transactional(readOnly = true)
    public AnalyzeResponse getById(UUID id, String callerEmail) {
        return toResponse(findOwned(id, callerEmail));
    }

    private CompatibilityAnalysis findOwned(UUID id, String callerEmail) {
        CompatibilityAnalysis analysis = repository.findById(id)
                .orElseThrow(() -> new CompatibilityAnalysisNotFoundException(id));

        if (!analysis.getOwnerEmail().equals(callerEmail)) {
            // deliberately the SAME exception as "doesn't exist" — mirrors
            // JobDescriptionService.findOwned in jd-resume-service, to avoid leaking existence
            // of another user's analysis.
            throw new CompatibilityAnalysisNotFoundException(id);
        }

        return analysis;
    }

    private AnalyzeResponse toResponse(CompatibilityAnalysis analysis) {
        // Null rather than an empty list before completion: "no gaps found" and "not scored yet"
        // must not look the same to a caller.
        List<SuggestionResponse> suggestions = analysis.getStatus() == AnalysisStatus.COMPLETED
                ? analysis.getSuggestions().stream()
                        .map(s -> new SuggestionResponse(s.getJdRequirement(), s.getSuggestion()))
                        .toList()
                : null;
        return new AnalyzeResponse(
                analysis.getId(), analysis.getJdId(), analysis.getResumeId(),
                analysis.getStatus(), analysis.getFailureReason(),
                analysis.getSkillsOverlap(), analysis.getExperienceMatch(),
                analysis.getKeywordCoverage(), analysis.getSemanticSimilarity(),
                suggestions, analysis.getCreatedAt(), analysis.getCompletedAt());
    }
}

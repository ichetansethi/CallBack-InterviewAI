package com.callback.session.service;

import com.callback.session.DTO.GeneratedFeedback;
import com.callback.session.DTO.QuestionFeedbackItem;
import com.callback.session.DTO.TranscriptTurnDto;
import com.callback.session.model.FeedbackSummary;
import com.callback.session.model.InterviewSession;
import com.callback.session.model.QuestionFeedback;
import com.callback.session.model.Speaker;
import com.callback.session.repository.FeedbackSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Generates post-session feedback from a completed transcript and persists it. Called from
 * SessionCompletedListener, so it must be safe to run more than once for the same session: Kafka
 * delivers at-least-once, and the listener's error handler re-runs the whole event after a
 * failure (a Groq 429 included). A session that already has feedback is skipped without calling
 * the model; FeedbackSummary's unique session_id is the backstop if two runs ever race.
 *
 * <p>Malformed model output (a score outside 1-10, a blank summary, no per-question items) is a
 * model mistake, so it's retried here by asking again, up to MAX_ATTEMPTS. Provider failures
 * (429s, timeouts) are deliberately NOT retried here — they propagate so the listener's backoff
 * can wait them out instead of immediately re-hitting the same limit.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ChatClient chatClient;
    private final FeedbackSummaryRepository feedbackRepository;

    public FeedbackService(ChatClient.Builder chatClientBuilder, FeedbackSummaryRepository feedbackRepository) {
        this.chatClient = chatClientBuilder.build();
        this.feedbackRepository = feedbackRepository;
    }

    public void generateAndPersist(InterviewSession session, List<TranscriptTurnDto> transcript) {
        if (feedbackRepository.existsBySessionId(session.getId())) {
            log.info("Feedback already exists for session {}, skipping", session.getId());
            return;
        }
        List<TranscriptTurnDto> ordered = transcript.stream()
                .sorted(Comparator.comparingInt(TranscriptTurnDto::turnIndex))
                .toList();
        if (ordered.stream().noneMatch(FeedbackService::isCandidateAnswer)) {
            // Nothing to evaluate — asking the model anyway just invites invented feedback.
            log.info("Session {} has no candidate answers, no feedback generated", session.getId());
            return;
        }

        GeneratedFeedback feedback = generateValidated(ordered);

        FeedbackSummary summary = new FeedbackSummary(
                session, feedback.overallSummary(), feedback.clarityScore(),
                feedback.structureScore(), feedback.technicalDepthScore()
        );
        List<QuestionFeedbackItem> items = feedback.perQuestion();
        summary.setPerQuestion(IntStream.range(0, items.size())
                .mapToObj(i -> new QuestionFeedback(summary, items.get(i).questionText(), items.get(i).feedbackText(), i))
                .toList()); // persisted via FeedbackSummary.perQuestion's cascade

        feedbackRepository.save(summary);
    }

    private GeneratedFeedback generateValidated(List<TranscriptTurnDto> transcript) {
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GeneratedFeedback feedback = chatClient.prompt()
                    .system("""
                    You are reviewing a completed mock interview transcript. Score clarity,
                    structure (STAR-method adherence), and technical depth from 1-10 each.
                    For each question actually asked, write feedback grounded specifically in
                    what the candidate said for that question — never generic advice.
                    """)
                    .user(buildTranscriptPrompt(transcript))
                    .call()
                    .entity(GeneratedFeedback.class);
            try {
                validate(feedback);
                return feedback;
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("Feedback generation attempt {}/{} returned invalid output: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw new IllegalStateException("Model failed to produce valid feedback after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    static void validate(GeneratedFeedback feedback) {
        if (feedback == null) {
            throw new IllegalStateException("no feedback returned");
        }
        if (feedback.overallSummary() == null || feedback.overallSummary().isBlank()) {
            throw new IllegalStateException("blank overallSummary");
        }
        requireScore("clarityScore", feedback.clarityScore());
        requireScore("structureScore", feedback.structureScore());
        requireScore("technicalDepthScore", feedback.technicalDepthScore());
        if (feedback.perQuestion() == null || feedback.perQuestion().isEmpty()) {
            throw new IllegalStateException("no perQuestion feedback");
        }
        for (QuestionFeedbackItem item : feedback.perQuestion()) {
            if (item.questionText() == null || item.questionText().isBlank()
                    || item.feedbackText() == null || item.feedbackText().isBlank()) {
                throw new IllegalStateException("perQuestion item with blank questionText/feedbackText");
            }
        }
    }

    private static void requireScore(String name, int score) {
        if (score < 1 || score > 10) {
            throw new IllegalStateException(name + " out of range 1-10: " + score);
        }
    }

    private static boolean isCandidateAnswer(TranscriptTurnDto turn) {
        return Speaker.CANDIDATE.name().equals(turn.speaker()) && turn.text() != null && !turn.text().isBlank();
    }

    private String buildTranscriptPrompt(List<TranscriptTurnDto> transcript) {
        StringBuilder sb = new StringBuilder("Transcript:\n");
        for (TranscriptTurnDto turn : transcript) {
            sb.append(turn.speaker()).append(": ").append(turn.text());
            if (turn.questionRationale() != null) {
                sb.append(" [question rationale: ").append(turn.questionRationale()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

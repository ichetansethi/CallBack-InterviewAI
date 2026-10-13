package com.callback.compatibility.service;

import com.callback.compatibility.rag.RagRetrievalService;
import com.callback.compatibility.scoring.CompatibilityScorer;
import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import com.callback.compatibility.scoring.RequirementEvidence;
import com.callback.compatibility.scoring.RequirementExtractor;
import com.callback.compatibility.scoring.ScoreBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The analysis itself: index the resume if needed, extract the JD's requirements, retrieve
 * per-requirement evidence, score via function calling. Takes the texts as parameters rather than
 * fetching them, because a Kafka consumer has no bearer token to relay; the request path fetches
 * them while the caller's token is still available. Persists nothing. Failures are tagged with
 * the stage they happened in (AnalysisStageException), so the listener can name the dependency.
 */
@Service
public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);
    private static final int EVIDENCE_TOP_K = 3;

    private final RagRetrievalService ragRetrievalService;
    private final RequirementExtractor requirementExtractor;
    private final CompatibilityScorer compatibilityScorer;

    public AnalysisPipeline(RagRetrievalService ragRetrievalService,
                            RequirementExtractor requirementExtractor,
                            CompatibilityScorer compatibilityScorer) {
        this.ragRetrievalService = ragRetrievalService;
        this.requirementExtractor = requirementExtractor;
        this.compatibilityScorer = compatibilityScorer;
    }

    public ScoreBreakdown run(String jdText, String resumeText, UUID resumeId) {
        resumeSearch(() -> {
            ragRetrievalService.indexResumeIfAbsent(resumeId, resumeText);
            return null;
        });

        List<String> requirements = aiModel(() -> requirementExtractor.extract(jdText));
        List<RequirementEvidence> evidence = requirements.stream()
                .map(requirement -> {
                    List<Document> retrieved = resumeSearch(() ->
                            ragRetrievalService.retrieveEvidence(resumeId, requirement, EVIDENCE_TOP_K));
                    log.info("Evidence scores for requirement \"{}\": {}", requirement,
                            retrieved.stream().map(Document::getScore).toList());
                    // Deliberately NOT filtered by similarity score — see CompatibilityScorer's
                    // class-level note: score alone doesn't reliably separate covered from not
                    // covered, so the scorer reads this raw text itself and judges from that.
                    return new RequirementEvidence(requirement, retrieved.stream().map(Document::getText).toList());
                })
                .toList();

        return aiModel(() -> compatibilityScorer.score(jdText, evidence));
    }

    private static <T> T resumeSearch(Supplier<T> step) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            throw new AnalysisStageException(AnalysisStageException.Stage.RESUME_SEARCH, e);
        }
    }

    /** A spent daily quota passes through untagged: it already names its dependency. */
    private static <T> T aiModel(Supplier<T> step) {
        try {
            return step.get();
        } catch (GroqQuotaExhaustedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AnalysisStageException(AnalysisStageException.Stage.AI_MODEL, e);
        }
    }
}

package com.callback.compatibility.service;

import com.callback.compatibility.model.AnalysisSuggestion;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import com.callback.compatibility.scoring.ScoreBreakdown;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every status change the listener makes, each in its own short transaction that reloads the
 * analysis by id. Two reasons this isn't inline in the listener: a @Transactional method called
 * from inside its own class bypasses the proxy and gets no transaction, and an entity loaded
 * outside a transaction is detached, so touching its lazy suggestions throws
 * LazyInitializationException. None of these transactions spans the LLM pipeline.
 */
@Service
public class AnalysisStateWriter {

    private final CompatibilityAnalysisRepository repository;

    public AnalysisStateWriter(CompatibilityAnalysisRepository repository) {
        this.repository = repository;
    }

    /**
     * Marks the analysis IN_PROGRESS and returns true if it should run. False when the row is gone
     * or already COMPLETED/FAILED, i.e. a duplicate delivery. An IN_PROGRESS row does run again:
     * that's an analysis whose previous run crashed before its offset was committed.
     */
    @Transactional
    public boolean startIfRunnable(UUID analysisId) {
        return repository.findById(analysisId)
                .filter(analysis -> !analysis.getStatus().isTerminal())
                .map(analysis -> {
                    analysis.markInProgress();
                    return true;
                })
                .orElse(false);
    }

    /** Scores, suggestions and COMPLETED in one transaction, so a crash never leaves a partial result. */
    @Transactional
    public void complete(UUID analysisId, ScoreBreakdown result) {
        List<AnalysisSuggestion> suggestions = result.suggestions().stream()
                .map(s -> new AnalysisSuggestion(s.jdRequirement(), s.suggestion()))
                .toList();
        repository.findById(analysisId).orElseThrow().complete(
                result.skillsOverlap(), result.experienceMatch(),
                result.keywordCoverage(), result.semanticSimilarity(), suggestions);
    }

    @Transactional
    public void fail(UUID analysisId, String reason) {
        repository.findById(analysisId).ifPresent(analysis -> analysis.markFailed(reason));
    }
}

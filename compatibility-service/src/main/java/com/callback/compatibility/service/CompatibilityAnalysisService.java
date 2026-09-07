package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.DTO.SuggestionResponse;
import com.callback.compatibility.client.JdResumeServiceClient;
import com.callback.compatibility.model.AnalysisSuggestion;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.rag.RagRetrievalService;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import com.callback.compatibility.scoring.CompatibilityScorer;
import com.callback.compatibility.scoring.RequirementEvidence;
import com.callback.compatibility.scoring.RequirementExtractor;
import com.callback.compatibility.scoring.ScoreBreakdown;
import com.callback.compatibility.scoring.TweakSuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a single analyze request: fetch JD + resume text from jd-resume-service (relaying
 * the caller's token, so jd-resume-service's own ownership checks are what actually gate access —
 * this service never re-implements that check), index the resume if needed, extract requirements,
 * retrieve per-requirement evidence, score via function calling, persist, respond.
 */
@Service
public class CompatibilityAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityAnalysisService.class);
    private static final int EVIDENCE_TOP_K = 3;

    private final JdResumeServiceClient jdResumeServiceClient;
    private final RagRetrievalService ragRetrievalService;
    private final RequirementExtractor requirementExtractor;
    private final CompatibilityScorer compatibilityScorer;
    private final CompatibilityAnalysisRepository repository;

    public CompatibilityAnalysisService(JdResumeServiceClient jdResumeServiceClient,
                                         RagRetrievalService ragRetrievalService,
                                         RequirementExtractor requirementExtractor,
                                         CompatibilityScorer compatibilityScorer,
                                         CompatibilityAnalysisRepository repository) {
        this.jdResumeServiceClient = jdResumeServiceClient;
        this.ragRetrievalService = ragRetrievalService;
        this.requirementExtractor = requirementExtractor;
        this.compatibilityScorer = compatibilityScorer;
        this.repository = repository;
    }

    public AnalyzeResponse analyze(UUID jdId, UUID resumeId, String ownerEmail, String authorizationHeader) {
        String jdText = jdResumeServiceClient.fetchJobDescriptionText(jdId, authorizationHeader);
        String resumeText = jdResumeServiceClient.fetchResumeText(resumeId, authorizationHeader);

        ragRetrievalService.indexResumeIfAbsent(resumeId, resumeText);

        List<String> requirements = requirementExtractor.extract(jdText);
        List<RequirementEvidence> evidence = requirements.stream()
                .map(requirement -> {
                    List<Document> retrieved = ragRetrievalService.retrieveEvidence(resumeId, requirement, EVIDENCE_TOP_K);
                    log.info("Evidence scores for requirement \"{}\": {}", requirement,
                            retrieved.stream().map(Document::getScore).toList());
                    // Deliberately NOT filtered by similarity score — see CompatibilityScorer's
                    // class-level note: score alone doesn't reliably separate covered from not
                    // covered, so the scorer reads this raw text itself and judges from that.
                    return new RequirementEvidence(requirement, retrieved.stream().map(Document::getText).toList());
                })
                .toList();

        ScoreBreakdown scoreBreakdown = compatibilityScorer.score(jdText, evidence);

        CompatibilityAnalysis analysis = new CompatibilityAnalysis(
                ownerEmail, jdId, resumeId,
                scoreBreakdown.skillsOverlap(), scoreBreakdown.experienceMatch(),
                scoreBreakdown.keywordCoverage(), scoreBreakdown.semanticSimilarity());
        for (TweakSuggestion suggestion : scoreBreakdown.suggestions()) {
            analysis.addSuggestion(new AnalysisSuggestion(suggestion.jdRequirement(), suggestion.suggestion()));
        }

        CompatibilityAnalysis saved = repository.save(analysis);
        return toResponse(saved);
    }

    private AnalyzeResponse toResponse(CompatibilityAnalysis analysis) {
        List<SuggestionResponse> suggestions = analysis.getSuggestions().stream()
                .map(s -> new SuggestionResponse(s.getJdRequirement(), s.getSuggestion()))
                .toList();
        return new AnalyzeResponse(
                analysis.getId(), analysis.getJdId(), analysis.getResumeId(),
                analysis.getSkillsOverlap(), analysis.getExperienceMatch(),
                analysis.getKeywordCoverage(), analysis.getSemanticSimilarity(),
                suggestions, analysis.getCreatedAt());
    }
}

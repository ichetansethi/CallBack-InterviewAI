package com.callback.compatibility.service;

import com.callback.compatibility.rag.RagRetrievalService;
import com.callback.compatibility.scoring.CompatibilityScorer;
import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import com.callback.compatibility.scoring.RequirementExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each stage's failure is tagged with the stage it happened in, which is what lets the failure
 * reason name Ollama/pgvector or Groq: their errors share exception types. Everything is mocked;
 * AnalysisPipelineTest runs the real pipeline.
 */
class AnalysisPipelineFailureTest {

    private static final UUID RESUME_ID = UUID.randomUUID();
    private static final ResourceAccessException OLLAMA_DOWN = new ResourceAccessException("Connection refused");
    private static final NonTransientAiException GROQ_401 = new NonTransientAiException("HTTP 401 - Invalid API Key");

    private final RagRetrievalService rag = mock(RagRetrievalService.class);
    private final RequirementExtractor extractor = mock(RequirementExtractor.class);
    private final CompatibilityScorer scorer = mock(CompatibilityScorer.class);
    private final AnalysisPipeline pipeline = new AnalysisPipeline(rag, extractor, scorer);

    @Test
    void indexingFailuresAreTaggedAsResumeSearch() {
        doThrow(OLLAMA_DOWN).when(rag).indexResumeIfAbsent(any(), anyString());

        assertThatThrownBy(() -> pipeline.run("JD", "Resume", RESUME_ID))
                .isInstanceOfSatisfying(AnalysisStageException.class,
                        e -> assertThat(e.stage())
                                .isEqualTo(AnalysisStageException.Stage.RESUME_SEARCH))
                .hasCause(OLLAMA_DOWN);
    }

    @Test
    void retrievalFailuresAreTaggedAsResumeSearch() {
        when(extractor.extract("JD")).thenReturn(List.of("Kafka"));
        when(rag.retrieveEvidence(any(), anyString(), anyInt())).thenThrow(OLLAMA_DOWN);

        assertThatThrownBy(() -> pipeline.run("JD", "Resume", RESUME_ID))
                .isInstanceOfSatisfying(AnalysisStageException.class,
                        e -> assertThat(e.stage())
                                .isEqualTo(AnalysisStageException.Stage.RESUME_SEARCH));
    }

    @Test
    void extractionAndScoringFailuresAreTaggedAsAiModel() {
        when(extractor.extract("JD")).thenThrow(GROQ_401);
        assertThatThrownBy(() -> pipeline.run("JD", "Resume", RESUME_ID))
                .isInstanceOfSatisfying(AnalysisStageException.class,
                        e -> assertThat(e.stage())
                                .isEqualTo(AnalysisStageException.Stage.AI_MODEL))
                .hasCause(GROQ_401);
    }

    @Test
    void scoringFailuresAreTaggedAsAiModel() {
        when(extractor.extract("JD")).thenReturn(List.of());
        when(scorer.score(anyString(), any())).thenThrow(GROQ_401);

        assertThatThrownBy(() -> pipeline.run("JD", "Resume", RESUME_ID))
                .isInstanceOfSatisfying(AnalysisStageException.class,
                        e -> assertThat(e.stage())
                                .isEqualTo(AnalysisStageException.Stage.AI_MODEL));
    }

    @Test
    void aSpentDailyQuotaPassesThroughUntagged() {
        GroqQuotaExhaustedException quota = new GroqQuotaExhaustedException(GROQ_401);
        when(extractor.extract("JD")).thenThrow(quota);

        assertThatThrownBy(() -> pipeline.run("JD", "Resume", RESUME_ID)).isSameAs(quota);
    }
}

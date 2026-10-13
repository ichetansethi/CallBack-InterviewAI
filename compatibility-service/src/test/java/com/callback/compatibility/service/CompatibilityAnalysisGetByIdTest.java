package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.exception.CompatibilityAnalysisNotFoundException;
import com.callback.compatibility.model.AnalysisStatus;
import com.callback.compatibility.model.AnalysisSuggestion;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for GET /compatibility/{id} returning 500 on every call (found while testing
 * question-service's compatibilityAnalysisId path through the gateway): getById read the lazy
 * suggestions collection after the repository's session had closed (open-in-view is off), so it
 * threw LazyInitializationException. Deliberately NOT @Transactional at class level — a test
 * transaction would keep the session open and hide exactly this bug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompatibilityAnalysisGetByIdTest {

    private static final String OWNER = "getbyid-owner@example.com";

    @Autowired
    private CompatibilityAnalysisService service;

    @Autowired
    private CompatibilityAnalysisRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID savedId;

    @AfterEach
    void cleanUp() {
        if (savedId != null) {
            repository.deleteById(savedId);
        }
    }

    @Test
    void getByIdReturnsPersistedSuggestions() {
        savedId = saveAnalysisWithSuggestion();

        AnalyzeResponse response = service.getById(savedId, OWNER);

        assertThat(response.id()).isEqualTo(savedId);
        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.completedAt()).isNotNull();
        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().getFirst().jdRequirement()).isEqualTo("Terraform");
    }

    @Test
    void getByIdForAnotherOwnerIsNotFound() {
        savedId = saveAnalysisWithSuggestion();

        assertThatThrownBy(() -> service.getById(savedId, "someone-else@example.com"))
                .isInstanceOf(CompatibilityAnalysisNotFoundException.class);
    }

    @Test
    void pendingAnalysisHasNoScoresOrSuggestions() {
        savedId = repository.save(CompatibilityAnalysis.pending(OWNER, UUID.randomUUID(), UUID.randomUUID())).getId();

        AnalyzeResponse response = service.getById(savedId, OWNER);

        assertThat(response.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(response.skillsOverlap()).isNull();
        assertThat(response.semanticSimilarity()).isNull();
        assertThat(response.suggestions()).isNull();
        assertThat(response.completedAt()).isNull();
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void failedAnalysisCarriesItsReason() {
        CompatibilityAnalysis analysis = CompatibilityAnalysis.pending(OWNER, UUID.randomUUID(), UUID.randomUUID());
        analysis.markFailed("Analysis could not be completed");
        savedId = repository.save(analysis).getId();

        AnalyzeResponse response = service.getById(savedId, OWNER);

        assertThat(response.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("Analysis could not be completed");
        assertThat(response.suggestions()).isNull();
        assertThat(response.completedAt()).isNotNull();
    }

    /** A re-run completes the same row again; the second result must replace the first, not add to it. */
    @Test
    void completingTwiceKeepsOnlyTheLatestSuggestions() {
        savedId = repository.save(CompatibilityAnalysis.pending(OWNER, UUID.randomUUID(), UUID.randomUUID())).getId();

        completeInTransaction(savedId, 50, List.of(new AnalysisSuggestion("Terraform", "first run")));
        completeInTransaction(savedId, 60, List.of(
                new AnalysisSuggestion("Terraform", "second run"),
                new AnalysisSuggestion("GraphQL", "second run")));

        AnalyzeResponse response = service.getById(savedId, OWNER);

        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.skillsOverlap()).isEqualTo(60);
        assertThat(response.suggestions()).extracting("suggestion").containsOnly("second run");
        assertThat(response.suggestions()).hasSize(2);
    }

    private void completeInTransaction(UUID id, int score, List<AnalysisSuggestion> suggestions) {
        transactionTemplate.executeWithoutResult(status ->
                repository.findById(id).orElseThrow().complete(score, score, score, score, suggestions));
    }

    private UUID saveAnalysisWithSuggestion() {
        CompatibilityAnalysis analysis = new CompatibilityAnalysis(
                OWNER, UUID.randomUUID(), UUID.randomUUID(), 80, 70, 60, 75);
        analysis.addSuggestion(new AnalysisSuggestion("Terraform", "Mention any infrastructure-as-code work."));
        return repository.save(analysis).getId();
    }

}

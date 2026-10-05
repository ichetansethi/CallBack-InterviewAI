package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.exception.CompatibilityAnalysisNotFoundException;
import com.callback.compatibility.model.AnalysisSuggestion;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().getFirst().jdRequirement()).isEqualTo("Terraform");
    }

    @Test
    void getByIdForAnotherOwnerIsNotFound() {
        savedId = saveAnalysisWithSuggestion();

        assertThatThrownBy(() -> service.getById(savedId, "someone-else@example.com"))
                .isInstanceOf(CompatibilityAnalysisNotFoundException.class);
    }

    private UUID saveAnalysisWithSuggestion() {
        CompatibilityAnalysis analysis = new CompatibilityAnalysis(
                OWNER, UUID.randomUUID(), UUID.randomUUID(), 80, 70, 60, 75);
        analysis.addSuggestion(new AnalysisSuggestion("Terraform", "Mention any infrastructure-as-code work."));
        return repository.save(analysis).getId();
    }

}

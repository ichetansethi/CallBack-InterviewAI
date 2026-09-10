package com.callback.question.service;

import com.callback.question.DTO.CompatibilityAnalysisResponse;
import com.callback.question.DTO.QuestionGenerateRequest;
import com.callback.question.DTO.QuestionSetResponse;
import com.callback.question.client.CompatibilityClient;
import com.callback.question.client.JdResumeClient;
import com.callback.question.repository.InterviewQuestionRepository;
import com.callback.question.repository.QuestionSetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Proves the full generate() flow end-to-end: a real QuestionGenerationService call (real Groq
 * model, real tool call) and real Postgres persistence through the actual repositories
 * (callback_question DB). Only the outbound clients are faked — JdResumeClient/CompatibilityClient
 * token-forwarding is already proven in their own client tests, so re-hitting live upstream
 * services here would add external dependencies without testing anything new for this stage.
 *
 * <p>Built in the same two stages as QuestionSetService itself: JD-only first
 * (generatesAndPersistsAQuestionSetFromJdTextAlone), then the gap-tailoring branch
 * (tailorsThePersistedBatchTowardTheCompatibilityAnalysissUncoveredRequirements) added once that
 * passed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuestionSetServiceTest {

    @Autowired
    private QuestionSetService questionSetService;

    @Autowired
    private QuestionSetRepository questionSetRepository;

    @Autowired
    private InterviewQuestionRepository interviewQuestionRepository;

    @MockitoBean
    private JdResumeClient jdResumeClient;

    @MockitoBean
    private CompatibilityClient compatibilityClient;

    @Test
    void generatesAndPersistsAQuestionSetFromJdTextAlone() {
        UUID jdId = UUID.randomUUID();
        String bearerToken = "Bearer caller-jwt";
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; PostgreSQL schema design;
                Kubernetes/AWS operations; Kafka event-driven architecture.""";
        when(jdResumeClient.getJdText(jdId, bearerToken)).thenReturn(jdText);

        QuestionGenerateRequest request = new QuestionGenerateRequest(jdId, null);

        QuestionSetResponse response = questionSetService.generate(request, "candidate@example.com", bearerToken);

        assertThat(response.id()).isNotNull();
        assertThat(response.jdId()).isEqualTo(jdId);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.questions()).isNotEmpty();
        response.questions().forEach(q -> {
            assertThat(q.category()).isNotBlank();
            assertThat(q.questionText()).isNotBlank();
            assertThat(q.rationale()).isNotBlank();
        });

        // Persisted for real, not just returned in the response.
        assertThat(questionSetRepository.findById(response.id())).isPresent();
        assertThat(interviewQuestionRepository.findAll())
                .filteredOn(iq -> iq.getQuestionSet().getId().equals(response.id()))
                .hasSameSizeAs(response.questions());

        org.mockito.Mockito.verifyNoInteractions(compatibilityClient);
    }

    @Test
    void tailorsThePersistedBatchTowardTheCompatibilityAnalysissUncoveredRequirements() {
        UUID jdId = UUID.randomUUID();
        UUID compatibilityAnalysisId = UUID.randomUUID();
        String bearerToken = "Bearer caller-jwt";
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; Terraform and Ansible infrastructure-as-code;
                Kafka event-driven architecture.""";
        when(jdResumeClient.getJdText(jdId, bearerToken)).thenReturn(jdText);

        CompatibilityAnalysisResponse analysis = new CompatibilityAnalysisResponse(
                compatibilityAnalysisId, jdId, UUID.randomUUID(),
                70, 65, 60, 75,
                List.of(new CompatibilityAnalysisResponse.SuggestionResponse(
                        "Terraform and Ansible infrastructure-as-code",
                        "Add resume content that specifically addresses: Terraform and Ansible infrastructure-as-code")),
                Instant.now());
        when(compatibilityClient.getAnalysis(compatibilityAnalysisId, bearerToken)).thenReturn(analysis);

        QuestionGenerateRequest request = new QuestionGenerateRequest(jdId, compatibilityAnalysisId);

        QuestionSetResponse response = questionSetService.generate(request, "candidate@example.com", bearerToken);

        assertThat(response.questions())
                .as("at least one persisted question should directly probe the uncovered requirement")
                .anyMatch(q -> {
                    String text = (q.questionText() + " " + q.rationale()).toLowerCase();
                    return text.contains("terraform") || text.contains("ansible") || text.contains("infrastructure");
                });

        assertThat(questionSetRepository.findById(response.id()).orElseThrow().getCompatibilityAnalysisId())
                .isEqualTo(compatibilityAnalysisId);
        org.mockito.Mockito.verify(compatibilityClient).getAnalysis(compatibilityAnalysisId, bearerToken);
    }
}

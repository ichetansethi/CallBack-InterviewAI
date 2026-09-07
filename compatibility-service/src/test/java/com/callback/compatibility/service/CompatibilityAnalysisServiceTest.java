package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalyzeResponse;
import com.callback.compatibility.client.JdResumeServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real bug found while smoke-testing the actual /compatibility/analyze
 * endpoint end to end: a short resume (few chunks) makes plain top-K retrieval always return
 * *something*, even for a requirement the resume never covers, so "empty evidence" alone never
 * signals a gap. CompatibilityAnalysisService now filters retrieved chunks by a similarity floor
 * before treating them as evidence — this test proves a genuine gap in a small resume still
 * surfaces as a suggestion once that fix is in place. jd-resume-service itself is mocked out
 * (network-isolated) since this test is about the RAG/scoring pipeline, not the HTTP boundary
 * already covered by JdResumeServiceClientTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompatibilityAnalysisServiceTest {

    @MockitoBean
    private JdResumeServiceClient jdResumeServiceClient;

    @Autowired
    private CompatibilityAnalysisService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID resumeId;

    @AfterEach
    void cleanUp() {
        if (resumeId != null) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'resumeId' = ?", resumeId.toString());
        }
    }

    @Test
    void aGenuineGapInAShortResumeStillProducesAGroundedSuggestion() {
        UUID jdId = UUID.randomUUID();
        resumeId = UUID.randomUUID();

        // Deliberately short — like the real short PDF resume that exposed this bug — so it
        // chunks into only a couple of pieces total.
        String resumeText = """
                Jane Doe
                Senior Software Engineer
                Experience: 7 years building backend services in Java and Python.
                Skills: Spring Boot, PostgreSQL, Kafka, AWS, Docker, Kubernetes.
                Education: B.S. Computer Science, State University.""";

        String jdText = """
                Senior Platform Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; Terraform and Ansible infrastructure-as-code;
                GraphQL API design; on-call incident response experience.""";

        when(jdResumeServiceClient.fetchJobDescriptionText(eq(jdId), any())).thenReturn(jdText);
        when(jdResumeServiceClient.fetchResumeText(eq(resumeId), any())).thenReturn(resumeText);

        AnalyzeResponse response = service.analyze(jdId, resumeId, "owner@example.com", "Bearer test-token");

        System.out.println("Response: " + response);
        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.suggestions())
                .anyMatch(s -> {
                    String req = s.jdRequirement().toLowerCase();
                    return req.contains("terraform") || req.contains("ansible") || req.contains("graphql")
                            || req.contains("on-call") || req.contains("incident");
                });
        response.suggestions().forEach(s -> assertThat(s.suggestion()).isNotBlank());
    }
}

package com.callback.compatibility.service;

import com.callback.compatibility.scoring.ScoreBreakdown;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug found while smoke-testing the actual /compatibility/analyze
 * endpoint end to end: a short resume (few chunks) makes plain top-K retrieval always return
 * *something*, even for a requirement the resume never covers, so "empty evidence" alone never
 * signals a gap. CompatibilityAnalysisService now filters retrieved chunks by a similarity floor
 * before treating them as evidence — this test proves a genuine gap in a small resume still
 * surfaces as a suggestion once that fix is in place. Calls the pipeline directly with texts, so
 * jd-resume-service isn't involved; that HTTP boundary is covered by JdResumeServiceClientTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AnalysisPipelineTest {

    @Autowired
    private AnalysisPipeline pipeline;

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

        ScoreBreakdown response = pipeline.run(jdText, resumeText, resumeId);

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

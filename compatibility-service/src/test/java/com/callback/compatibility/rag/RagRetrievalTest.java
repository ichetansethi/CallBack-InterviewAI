package com.callback.compatibility.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies chunking + similarity search actually returns sensible matches for a real resume,
 * standalone from any scoring logic. The fixture resume deliberately mixes a backend-engineering
 * history with an unrelated barista job, so a passing test proves discrimination, not just
 * "returns something". The vector store is a single shared, persistent pgvector table (not a
 * fresh in-memory store per test), so every test uses its own random resumeId and cleans up
 * after itself — otherwise tests would see each other's chunks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RagRetrievalTest {

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID resumeId;

    @AfterEach
    void cleanUp() {
        if (resumeId != null) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'resumeId' = ?", resumeId.toString());
        }
    }

    private UUID index() throws IOException {
        resumeId = UUID.randomUUID();
        String resumeText = new String(
                new ClassPathResource("fixtures/sample-resume.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        ragRetrievalService.indexResumeIfAbsent(resumeId, resumeText);
        return resumeId;
    }

    @Test
    void backendQueryRetrievesEngineeringExperienceNotTheBaristaJob() throws IOException {
        UUID id = index();

        List<Document> results = ragRetrievalService.retrieveEvidence(
                id, "Java Spring Boot backend microservices experience", 2);

        assertThat(results).isNotEmpty();
        String topText = results.get(0).getText().toLowerCase();
        assertThat(topText).containsAnyOf("java", "spring boot", "microservices", "kubernetes");
        assertThat(results).noneMatch(d -> d.getText().toLowerCase().contains("pastry"));
    }

    @Test
    void pastryQueryRetrievesTheBaristaJob() throws IOException {
        UUID id = index();

        List<Document> results = ragRetrievalService.retrieveEvidence(
                id, "pastry case espresso barista coffee shop", 1);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getText().toLowerCase()).contains("pastry");
    }

    @Test
    void relevantChunkScoresHigherThanIrrelevantChunkForTheSameQuery() throws IOException {
        UUID id = index();
        String query = "Java Spring Boot backend microservices experience";

        List<Document> results = ragRetrievalService.retrieveEvidence(id, query, 10);

        Document backendChunk = results.stream()
                .filter(d -> d.getText().toLowerCase().contains("spring boot"))
                .findFirst().orElseThrow();
        Document baristaChunk = results.stream()
                .filter(d -> d.getText().toLowerCase().contains("pastry"))
                .findFirst().orElseThrow();

        assertThat(backendChunk.getScore()).isGreaterThan(baristaChunk.getScore());
    }

    @Test
    void jdRequirementAbsentFromResumeGetsWeakEvidenceAndRetrievalVariesByQuery() throws IOException {
        UUID id = index();

        // Present in the resume: should retrieve strong, specific evidence.
        List<Document> kafkaResults = ragRetrievalService.retrieveEvidence(
                id, "Kafka event-driven architecture experience", 3);

        // Absent from the resume (fixture never mentions Terraform/Ansible/IaC at all).
        List<Document> terraformResults = ragRetrievalService.retrieveEvidence(
                id, "Terraform and Ansible infrastructure-as-code experience", 3);

        // A third, unrelated absent requirement, to prove retrieval isn't just returning a
        // static top-3 regardless of what's asked.
        List<Document> mlResults = ragRetrievalService.retrieveEvidence(
                id, "TensorFlow machine learning model training experience", 3);

        double kafkaTopScore = kafkaResults.get(0).getScore();
        double terraformTopScore = terraformResults.get(0).getScore();
        double mlTopScore = mlResults.get(0).getScore();

        System.out.println("Kafka (present) top score:     " + kafkaTopScore);
        System.out.println("Terraform (absent) top score:  " + terraformTopScore);
        System.out.println("ML (absent) top score:         " + mlTopScore);

        // The present requirement should score meaningfully higher than either absent one —
        // "weak/irrelevant evidence" for a gap, not a confident match.
        assertThat(kafkaTopScore).isGreaterThan(terraformTopScore);
        assertThat(kafkaTopScore).isGreaterThan(mlTopScore);

        // Retrieval must actually respond to the query, not always hand back the same top-3
        // regardless of input.
        List<String> kafkaIds = kafkaResults.stream().map(Document::getId).toList();
        List<String> terraformIds = terraformResults.stream().map(Document::getId).toList();
        List<String> mlIds = mlResults.stream().map(Document::getId).toList();
        assertThat(kafkaIds).isNotEqualTo(terraformIds);
        assertThat(terraformIds).isNotEqualTo(mlIds);
    }

    @Test
    void resumeIsNotReIndexedOnSecondCall() throws IOException {
        UUID id = UUID.randomUUID();
        resumeId = id;
        String resumeText = new String(
                new ClassPathResource("fixtures/sample-resume.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(ragRetrievalService.isIndexed(id)).isFalse();
        ragRetrievalService.indexResumeIfAbsent(id, resumeText);
        assertThat(ragRetrievalService.isIndexed(id)).isTrue();

        Integer countAfterFirstIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'resumeId' = ?", Integer.class, id.toString());

        ragRetrievalService.indexResumeIfAbsent(id, resumeText);

        Integer countAfterSecondCall = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'resumeId' = ?", Integer.class, id.toString());

        assertThat(countAfterSecondCall).isEqualTo(countAfterFirstIndex);
    }
}

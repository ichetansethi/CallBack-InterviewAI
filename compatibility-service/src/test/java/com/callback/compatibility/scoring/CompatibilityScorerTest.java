package com.callback.compatibility.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Function-calling scoring, tested with hand-built per-requirement evidence so this stage is
 * verified independently of the real extractor/retrieval stages. The model must call the
 * recordJudgment/submitScores tools rather than answer in prose — a passing test proves the tool
 * round-trip works, not just that the model said something plausible.
 *
 * <p>Originally scoped to a local 3B model (llama3.2:3b), which reliably (a) never returned a
 * suggestion with blank content — CompatibilityScorer rejects and retries a supported=false
 * judgment with no suggestion text — and (b) reliably flagged an evidence-free requirement as a
 * gap, but did NOT reliably avoid flagging a well-evidenced requirement too (observed false
 * positives on borderline-phrased requirements like "Kubernetes/AWS operations" even with clear
 * matching evidence). After switching to Groq's openai/gpt-oss-120b, that precision ceiling is
 * gone: re-run empirically on 2026-09-10, the well-covered case below now judges all four
 * requirements (including Kubernetes/AWS and Kafka) as met, with zero false-positive suggestions.
 * Assertions still don't hard-require perfect precision on the well-covered case, since this is a
 * non-deterministic model call, but the earlier "expect occasional false positives" framing no
 * longer reflects observed behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompatibilityScorerTest {

    @Autowired
    private CompatibilityScorer compatibilityScorer;

    @Test
    void wellCoveredEvidenceProducesOnlyWellFormedSuggestionsIfAny() {
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; PostgreSQL schema design;
                Kubernetes/AWS operations; Kafka event-driven architecture.""";

        List<RequirementEvidence> evidence = List.of(
                new RequirementEvidence("5+ years Java and Spring Boot",
                        List.of("Senior Backend Engineer, Nimbus Systems (2021 - Present). Led the migration to Spring Boot microservices. 7 years of Java experience.")),
                new RequirementEvidence("PostgreSQL schema design",
                        List.of("Designed the PostgreSQL schema for the new inventory service.")),
                new RequirementEvidence("Kubernetes/AWS operations",
                        List.of("Infrastructure: Docker, Kubernetes, AWS.")),
                new RequirementEvidence("Kafka event-driven architecture",
                        List.of("Microservices communicating over Kafka."))
        );

        ScoreBreakdown result = compatibilityScorer.score(jdText, evidence);

        System.out.println("Well-covered result: " + result);
        assertScoresInRange(result);
        // Every requirement here has clear, direct evidence. Groq's model now reliably avoids
        // flagging any of these as a gap (see class doc), but if a future run ever does, the
        // suggestion must still be genuine, specific text — never blank/decorative.
        result.suggestions().forEach(s -> assertThat(s.suggestion()).isNotBlank());
    }

    @Test
    void gapInEvidenceProducesASuggestionGroundedInThatSpecificRequirement() {
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; Terraform and Ansible infrastructure-as-code;
                Kafka event-driven architecture.""";

        List<RequirementEvidence> gappedEvidence = List.of(
                new RequirementEvidence("5+ years Java and Spring Boot",
                        List.of("7 years of Java and Spring Boot experience building microservices.")),
                new RequirementEvidence("Terraform and Ansible infrastructure-as-code",
                        List.of()), // no evidence at all — the resume never mentions IaC tooling
                new RequirementEvidence("Kafka event-driven architecture",
                        List.of("Microservices communicating over Kafka."))
        );

        ScoreBreakdown gappedResult = compatibilityScorer.score(jdText, gappedEvidence);

        System.out.println("Gapped result:  " + gappedResult);
        assertScoresInRange(gappedResult);

        // The one requirement with zero evidence is reliably caught, with a real suggestion.
        assertThat(gappedResult.suggestions())
                .anyMatch(s -> {
                    String req = s.jdRequirement().toLowerCase();
                    return req.contains("terraform") || req.contains("ansible") || req.contains("infrastructure");
                });
        gappedResult.suggestions().forEach(s -> assertThat(s.suggestion()).isNotBlank());
    }

    private void assertScoresInRange(ScoreBreakdown result) {
        assertThat(result.skillsOverlap()).isBetween(0, 100);
        assertThat(result.experienceMatch()).isBetween(0, 100);
        assertThat(result.keywordCoverage()).isBetween(0, 100);
        assertThat(result.semanticSimilarity()).isBetween(0, 100);
    }
}

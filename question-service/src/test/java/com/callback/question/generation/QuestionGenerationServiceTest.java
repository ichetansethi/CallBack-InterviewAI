package com.callback.question.generation;

import com.callback.question.DTO.GeneratedQuestion;
import com.callback.question.DTO.QuestionBatch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the generation stage in isolation, against the real chat model, with a hand-built job
 * description — no jd-resume-service/compatibility-service calls and no persistence involved.
 * The model must call the submitQuestions tool rather than answer in prose: a passing test proves
 * the tool round-trip works, not just that the model said something plausible. Boots only the
 * generation package (no datasource — question-service has no persistence wiring yet).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = QuestionGenerationServiceTest.TestConfig.class)
class QuestionGenerationServiceTest {

    @SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
    @ComponentScan(basePackageClasses = QuestionGenerationService.class)
    static class TestConfig {
    }

    @Autowired
    private QuestionGenerationService questionGenerationService;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private static final Set<String> KNOWN_CATEGORIES = Set.of("technical", "behavioral", "role-specific");

    /**
     * submitQuestions takes a nested List&lt;GeneratedQuestion&gt; argument — unlike
     * compatibility-service's tools, which only ever take flat scalar arguments
     * (recordJudgment's boolean+String, submitScores' four ints). A nested-list argument is a
     * materially different shape for the model to fill in correctly, and it's exactly the shape
     * that caused real problems on the small local model tried during compatibility-service
     * development (malformed/missing arguments once several tool calls had accumulated in the
     * same exchange). Now that question-service starts on Groq from day one (openai/gpt-oss-120b —
     * llama-3.3-70b-versatile was tried first but Groq no longer serves it on this account, see
     * application.yml history), this checks that specific shape directly and early — several
     * independent, non-retried calls against the exact production prompt (via
     * QuestionGenerationService.buildSystemPrompt/buildUserPrompt, not a hand-duplicated copy that
     * could drift from it), bypassing QuestionGenerationService's own retry loop, which would
     * otherwise mask a flaky first attempt — rather than assuming a bigger hosted model makes it a
     * non-issue.
     */
    @Test
    void theNestedListToolCallArgumentComesBackWellFormedAcrossRepeatedCalls() {
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; PostgreSQL schema design;
                Kubernetes/AWS operations; Kafka event-driven architecture; gRPC API design;
                CI/CD pipeline ownership; on-call incident response.""";

        ChatClient chatClient = chatClientBuilder.build();
        int trials = 5;
        int successes = 0;

        for (int i = 1; i <= trials; i++) {
            QuestionBatchRecorder recorder = new QuestionBatchRecorder();
            try {
                chatClient.prompt()
                        .system(QuestionGenerationService.buildSystemPrompt())
                        .user(QuestionGenerationService.buildUserPrompt(jdText, List.of()))
                        .tools(recorder)
                        .call()
                        .content();

                QuestionBatch batch = recorder.result().orElseThrow(() ->
                        new IllegalStateException("Model did not call submitQuestions"));

                assertThat(batch.questions()).as("trial %d: nested list arg", i).isNotEmpty();
                for (GeneratedQuestion q : batch.questions()) {
                    assertThat(q.category()).as("trial %d: category", i).isNotBlank();
                    assertThat(q.questionText()).as("trial %d: questionText", i).isNotBlank();
                    assertThat(q.rationale()).as("trial %d: rationale", i).isNotBlank();
                    assertThat(KNOWN_CATEGORIES).as("trial %d: category value \"%s\"", i, q.category())
                            .contains(q.category().toLowerCase());
                }
                successes++;
            } catch (RuntimeException e) {
                System.out.println("Trial " + i + " failed to produce a well-formed nested list: " + e);
            }
        }

        System.out.println("Nested-list tool-call argument: " + successes + "/" + trials
                + " raw (non-retried) attempts well-formed");
        // Allow at most one flaky miss out of 5 raw attempts — QuestionGenerationService's own
        // retry loop exists precisely to absorb that, but a majority-failing rate here would mean
        // the nested-list shape itself is unreliable, not just noisy.
        assertThat(successes).isGreaterThanOrEqualTo(trials - 1);
    }

    @Test
    void generatesAQuestionBatchGroundedInTheJobDescription() {
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; PostgreSQL schema design;
                Kubernetes/AWS operations; Kafka event-driven architecture.""";

        QuestionBatch batch = questionGenerationService.generate(jdText, List.of());

        System.out.println("Generated batch (no uncovered requirements): " + batch);
        assertThat(batch.questions()).isNotEmpty();
        for (GeneratedQuestion q : batch.questions()) {
            assertThat(q.category()).isNotBlank();
            assertThat(q.questionText()).isNotBlank();
            assertThat(q.rationale()).isNotBlank();
        }
        logCategoryBreakdown(batch);
    }

    @Test
    void tailorsQuestionsTowardSuppliedUncoveredRequirements() {
        String jdText = """
                Senior Backend Engineer - Nimbus Systems
                Requirements: 5+ years Java and Spring Boot; Terraform and Ansible infrastructure-as-code;
                Kafka event-driven architecture.""";

        // The actual JD requirement compatibility-service found unsupported by the resume —
        // not resume-tailoring advice text, which is a different field on its SuggestionResponse.
        List<String> uncoveredRequirements = List.of("Terraform and Ansible infrastructure-as-code");

        QuestionBatch batch = questionGenerationService.generate(jdText, uncoveredRequirements);

        System.out.println("Generated batch (with uncovered requirements): " + batch);
        assertThat(batch.questions()).isNotEmpty();
        assertThat(batch.questions())
                .as("at least 1-2 questions should directly probe the uncovered requirement")
                .anyMatch(q -> {
                    String text = (q.questionText() + " " + q.rationale()).toLowerCase();
                    return text.contains("terraform") || text.contains("ansible") || text.contains("infrastructure");
                });
        logCategoryBreakdown(batch);
    }

    private void logCategoryBreakdown(QuestionBatch batch) {
        var counts = batch.questions().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        q -> q.category().toLowerCase(), java.util.stream.Collectors.counting()));
        System.out.println("Category breakdown: " + counts + " (target: 3 technical / 2 behavioral / 2 role-specific)");
    }
}

package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.model.AnalysisStatus;
import com.callback.compatibility.model.AnalysisSuggestion;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import com.callback.compatibility.scoring.ScoreBreakdown;
import com.callback.compatibility.scoring.TweakSuggestion;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The listener's own behaviour, against the real broker and database, with the LLM pipeline
 * mocked so outcomes are deterministic and no Groq budget is spent. Uses its own consumer group,
 * so it never takes events from a running compatibility-service.
 *
 * Every event is keyed by OWNER, so they all land on one partition and are handled in order. To
 * prove that something did NOT happen (a second run, a retry), a test publishes a sentinel after it
 * and waits for the sentinel to complete: by then everything before it has been fully handled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "compatibility.kafka.listener.auto-startup=true",
        "compatibility.kafka.group-id=compatibility-service-listener-test"})
class AnalysisRequestedListenerTest {

    private static final String OWNER = "listener-test@example.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @MockitoBean
    private AnalysisPipeline pipeline;

    @Autowired
    private KafkaTemplate<String, AnalysisRequestedEvent> kafkaTemplate;

    @Autowired
    private CompatibilityAnalysisRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${compatibility.kafka.topic}")
    private String topic;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @AfterEach
    void cleanUp() {
        repository.deleteAll(repository.findByOwnerEmail(OWNER));
    }

    @Test
    void completesAnAnalysisWithItsScoresAndSuggestions() {
        when(pipeline.run(any(), any(), any())).thenReturn(result(80, "Terraform", "GraphQL"));
        AnalysisRequestedEvent event = eventFor(savePending());

        publish(event);

        CompatibilityAnalysis done = awaitStatus(event.analysisId(), AnalysisStatus.COMPLETED);
        assertThat(done.getSkillsOverlap()).isEqualTo(80);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(suggestionRequirements(event.analysisId())).containsExactlyInAnyOrder("Terraform", "GraphQL");
        verify(pipeline).run("JD text", "Resume text", event.resumeId());
    }

    @Test
    void aPipelineFailureIsRecordedOnceAndNeverRedelivered() {
        AnalysisRequestedEvent failing = eventFor(savePending());
        AnalysisRequestedEvent sentinel = eventFor(savePending());
        when(pipeline.run(any(), any(), eq(failing.resumeId()))).thenThrow(new AnalysisStageException(AnalysisStageException.Stage.AI_MODEL,
                new NonTransientAiException("HTTP 401 - {\"error\":{\"message\":\"Invalid API Key\"}}")));
        when(pipeline.run(any(), any(), eq(sentinel.resumeId()))).thenReturn(result(50));

        publish(failing);
        publish(sentinel);

        awaitStatus(sentinel.analysisId(), AnalysisStatus.COMPLETED);
        CompatibilityAnalysis failed = repository.findById(failing.analysisId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(AnalysisFailureReasons.AI_KEY_REJECTED);
        verify(pipeline, times(1)).run(any(), any(), eq(failing.resumeId()));
    }

    @Test
    void aDuplicateDeliveryRunsThePipelineOnceAndLeavesOneResultSet() {
        AnalysisRequestedEvent event = eventFor(savePending());
        AnalysisRequestedEvent sentinel = eventFor(savePending());
        when(pipeline.run(any(), any(), eq(event.resumeId()))).thenReturn(result(70, "Terraform", "GraphQL"));
        when(pipeline.run(any(), any(), eq(sentinel.resumeId()))).thenReturn(result(50));

        publish(event);
        publish(event);
        publish(sentinel);

        awaitStatus(sentinel.analysisId(), AnalysisStatus.COMPLETED);
        assertThat(repository.findById(event.analysisId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(suggestionRequirements(event.analysisId())).hasSize(2);
        verify(pipeline, times(1)).run(any(), any(), eq(event.resumeId()));
    }

    /** IN_PROGRESS with no result means the previous run crashed before its offset was committed. */
    @Test
    void anAnalysisLeftInProgressByACrashIsRunAgain() {
        UUID id = savePending();
        transactionTemplate.executeWithoutResult(s -> repository.findById(id).orElseThrow().markInProgress());
        when(pipeline.run(any(), any(), any())).thenReturn(result(65, "Terraform"));

        publish(eventFor(id));

        awaitStatus(id, AnalysisStatus.COMPLETED);
        assertThat(suggestionRequirements(id)).containsExactly("Terraform");
    }

    @Test
    void anEventWhoseRowIsGoneIsSkipped() {
        AnalysisRequestedEvent orphan = eventFor(UUID.randomUUID());
        AnalysisRequestedEvent sentinel = eventFor(savePending());
        when(pipeline.run(any(), any(), eq(sentinel.resumeId()))).thenReturn(result(50));

        publish(orphan);
        publish(sentinel);

        awaitStatus(sentinel.analysisId(), AnalysisStatus.COMPLETED);
        verify(pipeline, never()).run(any(), any(), eq(orphan.resumeId()));
    }

    @Test
    void aGarbageRecordIsSkippedAndTheConsumerCarriesOn() {
        AnalysisRequestedEvent sentinel = eventFor(savePending());
        when(pipeline.run(any(), any(), eq(sentinel.resumeId()))).thenReturn(result(50));

        KafkaTemplate<String, String> raw = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(), new StringSerializer()));
        raw.send(topic, OWNER, "this is not json").join();
        raw.destroy();
        publish(sentinel);

        awaitStatus(sentinel.analysisId(), AnalysisStatus.COMPLETED);
    }

    private UUID savePending() {
        return repository.save(CompatibilityAnalysis.pending(OWNER, UUID.randomUUID(), UUID.randomUUID())).getId();
    }

    private AnalysisRequestedEvent eventFor(UUID analysisId) {
        UUID resumeId = repository.findById(analysisId).map(CompatibilityAnalysis::getResumeId).orElse(UUID.randomUUID());
        return new AnalysisRequestedEvent(analysisId, OWNER, UUID.randomUUID(), resumeId,
                "JD text", "Resume text", Instant.now());
    }

    private void publish(AnalysisRequestedEvent event) {
        kafkaTemplate.send(topic, OWNER, event).join();
    }

    private CompatibilityAnalysis awaitStatus(UUID id, AnalysisStatus status) {
        await().atMost(TIMEOUT).until(() -> repository.findById(id).map(CompatibilityAnalysis::getStatus).orElse(null) == status);
        return repository.findById(id).orElseThrow();
    }

    private List<String> suggestionRequirements(UUID id) {
        return transactionTemplate.execute(s -> repository.findById(id).orElseThrow().getSuggestions().stream()
                .map(AnalysisSuggestion::getJdRequirement).toList());
    }

    private static ScoreBreakdown result(int score, String... uncovered) {
        List<TweakSuggestion> suggestions = Arrays.stream(uncovered)
                .map(requirement -> new TweakSuggestion(requirement, "Mention " + requirement + " work."))
                .toList();
        return new ScoreBreakdown(score, score, score, score, suggestions);
    }
}

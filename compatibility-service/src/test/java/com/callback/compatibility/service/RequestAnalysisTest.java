package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisAcceptedResponse;
import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.client.JdResumeServiceClient;
import com.callback.compatibility.exception.AnalysisQueueUnavailableException;
import com.callback.compatibility.exception.UpstreamNotFoundException;
import com.callback.compatibility.model.AnalysisStatus;
import com.callback.compatibility.model.CompatibilityAnalysis;
import com.callback.compatibility.repository.CompatibilityAnalysisRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The request side of an analysis, against the real broker and database. jd-resume-service is
 * mocked; its HTTP boundary is covered by JdResumeServiceClientTest. The happy path leaves its
 * event on analysis-requested, but cleanUp deletes the row it points to, so the listener skips it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequestAnalysisTest {

    private static final String OWNER = "request-analysis-test@example.com";
    private static final String BEARER = "Bearer test-token";

    @MockitoBean
    private JdResumeServiceClient jdResumeServiceClient;

    @MockitoSpyBean
    private KafkaTemplate<String, AnalysisRequestedEvent> kafkaTemplate;

    @Autowired
    private CompatibilityAnalysisService service;

    @Autowired
    private CompatibilityAnalysisRepository repository;

    @Autowired
    private ConsumerFactory<String, AnalysisRequestedEvent> consumerFactory;

    @Value("${compatibility.kafka.topic}")
    private String topic;

    @AfterEach
    void cleanUp() {
        repository.deleteAll(repository.findByOwnerEmail(OWNER));
    }

    @Test
    void savesAPendingRowAndPublishesTheTextsKeyedByOwner() {
        UUID jdId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        when(jdResumeServiceClient.fetchJobDescriptionText(jdId, BEARER)).thenReturn("JD text");
        when(jdResumeServiceClient.fetchResumeText(resumeId, BEARER)).thenReturn("Resume text");

        try (Consumer<String, AnalysisRequestedEvent> consumer = consumerAtEndOfTopic()) {
            Instant before = Instant.now();
            AnalysisAcceptedResponse response = service.requestAnalysis(jdId, resumeId, OWNER, BEARER);

            assertThat(response.status()).isEqualTo(AnalysisStatus.PENDING);
            CompatibilityAnalysis saved = repository.findById(response.analysisId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(saved.getOwnerEmail()).isEqualTo(OWNER);

            ConsumerRecord<String, AnalysisRequestedEvent> published = pollFor(consumer, response.analysisId());
            assertThat(published.key()).isEqualTo(OWNER);
            AnalysisRequestedEvent event = published.value();
            assertThat(event.ownerEmail()).isEqualTo(OWNER);
            assertThat(event.jdId()).isEqualTo(jdId);
            assertThat(event.resumeId()).isEqualTo(resumeId);
            assertThat(event.jdText()).isEqualTo("JD text");
            assertThat(event.resumeText()).isEqualTo("Resume text");
            assertThat(event.requestedAt()).isAfterOrEqualTo(before);
        }
    }

    @Test
    void aJdTheCallerDoesNotOwnIsRejectedBeforeAnythingIsSavedOrQueued() {
        UUID jdId = UUID.randomUUID();
        when(jdResumeServiceClient.fetchJobDescriptionText(jdId, BEARER))
                .thenThrow(new UpstreamNotFoundException("Job description " + jdId + " not found"));

        assertThatThrownBy(() -> service.requestAnalysis(jdId, UUID.randomUUID(), OWNER, BEARER))
                .isInstanceOf(UpstreamNotFoundException.class);

        assertThat(repository.findByOwnerEmail(OWNER)).isEmpty();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aFailedPublishMarksTheRowFailedInsteadOfLeavingItPending() {
        UUID jdId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        when(jdResumeServiceClient.fetchJobDescriptionText(jdId, BEARER)).thenReturn("JD text");
        when(jdResumeServiceClient.fetchResumeText(resumeId, BEARER)).thenReturn("Resume text");
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker down")))
                .when(kafkaTemplate).send(eq(topic), eq(OWNER), any(AnalysisRequestedEvent.class));

        assertThatThrownBy(() -> service.requestAnalysis(jdId, resumeId, OWNER, BEARER))
                .isInstanceOf(AnalysisQueueUnavailableException.class);

        List<CompatibilityAnalysis> rows = repository.findByOwnerEmail(OWNER);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(rows.getFirst().getFailureReason()).isEqualTo("Could not queue analysis");
        assertThat(rows.getFirst().getCompletedAt()).isNotNull();
    }

    /** Assigned (no group membership, no committed offsets) and positioned past every existing record. */
    private Consumer<String, AnalysisRequestedEvent> consumerAtEndOfTopic() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        Consumer<String, AnalysisRequestedEvent> consumer =
                consumerFactory.createConsumer("request-analysis-test-" + UUID.randomUUID(), null, null, overrides);
        List<TopicPartition> partitions = IntStream.range(0, consumer.partitionsFor(topic).size())
                .mapToObj(p -> new TopicPartition(topic, p)).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // seekToEnd is lazy; resolve it before publishing
        return consumer;
    }

    private ConsumerRecord<String, AnalysisRequestedEvent> pollFor(
            Consumer<String, AnalysisRequestedEvent> consumer, UUID analysisId) {
        List<ConsumerRecord<String, AnalysisRequestedEvent>> seen = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
            for (ConsumerRecord<String, AnalysisRequestedEvent> record : seen) {
                if (record.value() != null && analysisId.equals(record.value().analysisId())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No event for analysis " + analysisId + " within 10s; saw " + seen.size() + " other records");
    }
}

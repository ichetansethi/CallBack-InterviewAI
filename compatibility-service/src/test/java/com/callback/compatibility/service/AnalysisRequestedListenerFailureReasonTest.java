package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which failure reason the listener records, without a broker: AnalysisRequestedListenerTest
 * covers delivery against the real one, and AnalysisFailureReasonsTest every reason. A spent daily
 * quota says so, a failed dependency is named, and a failure from no dependency stays generic.
 */
class AnalysisRequestedListenerFailureReasonTest {

    private final AnalysisPipeline pipeline = mock(AnalysisPipeline.class);
    private final AnalysisStateWriter stateWriter = mock(AnalysisStateWriter.class);
    private final AnalysisRequestedListener listener = new AnalysisRequestedListener(pipeline, stateWriter);
    private final AnalysisRequestedEvent event = new AnalysisRequestedEvent(UUID.randomUUID(), "owner@example.com",
            UUID.randomUUID(), UUID.randomUUID(), "JD text", "Resume text", Instant.now());

    @Test
    void aSpentDailyQuotaIsRecordedAsSuch() {
        when(stateWriter.startIfRunnable(event.analysisId())).thenReturn(true);
        when(pipeline.run(any(), any(), any()))
                .thenThrow(new GroqQuotaExhaustedException(new RuntimeException("HTTP 429 - tokens per day (TPD)")));

        listener.handle(event);

        verify(stateWriter).fail(event.analysisId(), AnalysisFailureReasons.AI_DAILY_QUOTA);
    }

    @Test
    void aFailedDependencyIsNamed() {
        when(stateWriter.startIfRunnable(event.analysisId())).thenReturn(true);
        when(pipeline.run(any(), any(), any())).thenThrow(new AnalysisStageException(
                AnalysisStageException.Stage.RESUME_SEARCH,
                new ResourceAccessException("I/O error on POST request for \"http://localhost:11434/api/embed\": null")));

        listener.handle(event);

        verify(stateWriter).fail(event.analysisId(), AnalysisFailureReasons.EMBEDDINGS_UNAVAILABLE);
    }

    @Test
    void aFailureFromNoDependencyKeepsTheGenericReason() {
        when(stateWriter.startIfRunnable(event.analysisId())).thenReturn(true);
        when(pipeline.run(any(), any(), any())).thenThrow(new NullPointerException());

        listener.handle(event);

        verify(stateWriter).fail(event.analysisId(), AnalysisFailureReasons.GENERIC);
    }
}

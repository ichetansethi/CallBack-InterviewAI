package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which failure reason the listener records, without a broker: AnalysisRequestedListenerTest
 * covers delivery against the real one. A spent daily quota says so and that a later resubmission
 * will work; any other failure keeps the generic reason.
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

        verify(stateWriter).fail(event.analysisId(), AnalysisRequestedListener.QUOTA_FAILURE_REASON);
    }

    @Test
    void anyOtherFailureKeepsTheGenericReason() {
        when(stateWriter.startIfRunnable(event.analysisId())).thenReturn(true);
        when(pipeline.run(any(), any(), any())).thenThrow(new IllegalStateException("All 3 judgment votes failed"));

        listener.handle(event);

        verify(stateWriter).fail(event.analysisId(), AnalysisRequestedListener.FAILURE_REASON);
    }
}

package com.callback.compatibility.service;

import com.callback.compatibility.DTO.AnalysisRequestedEvent;
import com.callback.compatibility.scoring.GroqQuotaExhaustedException;
import com.callback.compatibility.scoring.ScoreBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs queued analyses one at a time. Concurrency stays at 1 because Groq's tokens-per-minute
 * limit is the bottleneck, not compute: more consumers would only produce more 429s. The topic
 * absorbs bursts and feeds the LLM at a steady pace.
 *
 * Delivery is at-least-once, so this is idempotent: terminal analyses are skipped, and an
 * IN_PROGRESS one (a previous run crashed) runs again from scratch. Failures are recorded as
 * FAILED and never rethrown, because a redelivery would re-run the whole LLM pipeline against the
 * same rate limit; the user resubmits instead. The failure reason names the dependency that broke
 * (see AnalysisFailureReasons).
 */
@Component
public class AnalysisRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestedListener.class);

    private final AnalysisPipeline pipeline;
    private final AnalysisStateWriter stateWriter;

    public AnalysisRequestedListener(AnalysisPipeline pipeline, AnalysisStateWriter stateWriter) {
        this.pipeline = pipeline;
        this.stateWriter = stateWriter;
    }

    @KafkaListener(topics = "${compatibility.kafka.topic}", groupId = "${compatibility.kafka.group-id}",
            containerFactory = "analysisListenerContainerFactory", concurrency = "1",
            autoStartup = "${compatibility.kafka.listener.auto-startup:true}")
    public void handle(AnalysisRequestedEvent event) {
        UUID analysisId = event.analysisId();
        if (!stateWriter.startIfRunnable(analysisId)) {
            log.info("Skipping analysis {}: no such row, or already finished (duplicate delivery)", analysisId);
            return;
        }

        log.info("Running analysis {}", analysisId);
        long startNanos = System.nanoTime();
        try {
            ScoreBreakdown result = pipeline.run(event.jdText(), event.resumeText(), event.resumeId());
            stateWriter.complete(analysisId, result);
            log.info("Analysis {} completed in {}s", analysisId, (System.nanoTime() - startNanos) / 1_000_000_000);
        } catch (GroqQuotaExhaustedException e) {
            log.warn("Analysis {} failed after {}s: {}", analysisId,
                    (System.nanoTime() - startNanos) / 1_000_000_000, e.getMessage());
            stateWriter.fail(analysisId, AnalysisFailureReasons.AI_DAILY_QUOTA);
        } catch (Exception e) {
            log.error("Analysis {} failed after {}s", analysisId, (System.nanoTime() - startNanos) / 1_000_000_000, e);
            stateWriter.fail(analysisId, AnalysisFailureReasons.of(e));
        }
    }
}

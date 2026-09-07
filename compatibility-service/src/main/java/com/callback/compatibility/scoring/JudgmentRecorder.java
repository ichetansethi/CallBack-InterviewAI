package com.callback.compatibility.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * Exposes ONLY recordJudgment — used one requirement at a time (see CompatibilityScorer), never
 * across a batch. Cosine-similarity scores alone were found not to reliably separate "resume
 * covers this" from "resume doesn't" (genuinely-covered requirements scored anywhere from ~0.49 to
 * ~0.59 for the same resume, fully overlapping the range seen for genuinely-absent ones), so the
 * model reads the actual evidence text and judges it directly instead. A fresh instance per call.
 */
public class JudgmentRecorder {

    private static final Logger log = LoggerFactory.getLogger(JudgmentRecorder.class);

    private Judgment result;

    @Tool(description = "Record whether the resume evidence explicitly supports the job requirement, and if not, a suggestion to address the gap. Call exactly once.")
    public String recordJudgment(
            @ToolParam(description = "true if the evidence explicitly and directly supports the requirement; false if it's absent, about something else, or only vaguely related") boolean supported,
            @ToolParam(description = "If supported is false: a concrete, specific resume addition that would address this requirement. If supported is true: an empty string.") String suggestionIfNotSupported) {
        log.info("TOOL CALL recordJudgment invoked by model: supported={}, suggestion={}", supported, suggestionIfNotSupported);
        this.result = new Judgment(supported, suggestionIfNotSupported);
        return "recorded";
    }

    public Optional<Judgment> result() {
        return Optional.ofNullable(result);
    }
}

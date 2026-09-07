package com.callback.compatibility.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * Exposes ONLY submitScores as a tool, in its own independent model exchange (no preceding
 * addSuggestion calls in context) — a local 3B model was observed to reliably call this when it's
 * the only/first tool call in the conversation, but to occasionally emit a malformed argument for
 * it after several addSuggestion calls had already accumulated in the same exchange. A fresh
 * instance per scoring call.
 */
public class ScoreOnlyRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScoreOnlyRecorder.class);

    private int[] scores;

    @Tool(description = "Submit the four compatibility scores between the job description and the resume evidence. Call this exactly once.")
    public String submitScores(
            @ToolParam(description = "Score 0-100 for how well the resume's skills overlap the JD's required skills") int skillsOverlap,
            @ToolParam(description = "Score 0-100 for how well the resume's experience level/years matches the JD's requirements") int experienceMatch,
            @ToolParam(description = "Score 0-100 for keyword/terminology coverage between the JD and the resume evidence") int keywordCoverage,
            @ToolParam(description = "Score 0-100 for overall semantic similarity between the JD and the resume evidence") int semanticSimilarity) {
        log.info("TOOL CALL submitScores invoked by model: skillsOverlap={}, experienceMatch={}, keywordCoverage={}, semanticSimilarity={}",
                skillsOverlap, experienceMatch, keywordCoverage, semanticSimilarity);
        this.scores = new int[] {skillsOverlap, experienceMatch, keywordCoverage, semanticSimilarity};
        return "recorded";
    }

    public Optional<int[]> scores() {
        return Optional.ofNullable(scores);
    }
}

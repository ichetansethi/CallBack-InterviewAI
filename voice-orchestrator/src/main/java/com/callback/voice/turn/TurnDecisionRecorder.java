package com.callback.voice.turn;

import com.callback.voice.DTO.TurnDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * Exposes ONLY submitTurnDecision — the model must respond with a real tool call rather than free
 * text. Same single-tool-exchange discipline as QuestionBatchRecorder / JudgmentRecorder. A fresh
 * instance per decision call.
 */
public class TurnDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnDecisionRecorder.class);

    private TurnDecision decision;

    @Tool(description = "Submit the interviewer's next-turn decision. Call exactly once.")
    public String submitTurnDecision(
            @ToolParam(description = "Either 'follow_up' (probe the candidate's last answer further) "
                    + "or 'advance' (move on to the next prepared question)")
            String action,
            @ToolParam(description = "The exact text the interviewer should say next")
            String responseText) {
        log.info("TOOL CALL submitTurnDecision invoked by model: action={}", action);
        this.decision = new TurnDecision(action, responseText);
        return "recorded";
    }

    public Optional<TurnDecision> result() {
        return Optional.ofNullable(decision);
    }

}

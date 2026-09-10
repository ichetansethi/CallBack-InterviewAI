package com.callback.question.generation;

import com.callback.question.DTO.GeneratedQuestion;
import com.callback.question.DTO.QuestionBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Optional;

/**
 * Exposes ONLY submitQuestions — the model must respond with a real tool call carrying the
 * complete QuestionBatch rather than free text, so the result is either exactly what the schema
 * requires or it visibly fails, never a batch that "almost" matches after hand-parsing prose.
 * A fresh instance per generation call.
 */
public class QuestionBatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(QuestionBatchRecorder.class);

    private QuestionBatch batch;

    @Tool(description = "Submit the complete set of generated interview questions. Call exactly once.")
    public String submitQuestions(
            @ToolParam(description = "The generated interview questions, each with a category "
                    + "('technical', 'behavioral', or 'role-specific'), the question text, and a rationale "
                    + "grounded in the job description (and gap suggestions, if provided)")
            List<GeneratedQuestion> questions) {
        log.info("TOOL CALL submitQuestions invoked by model: {} question(s)", questions.size());
        this.batch = new QuestionBatch(questions);
        return "recorded";
    }

    public Optional<QuestionBatch> result() {
        return Optional.ofNullable(batch);
    }
}

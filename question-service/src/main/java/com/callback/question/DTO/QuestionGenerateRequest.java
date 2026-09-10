package com.callback.question.DTO;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record QuestionGenerateRequest(
        @NotNull UUID jdId,
        UUID compatibilityAnalysisId // optional
) {}
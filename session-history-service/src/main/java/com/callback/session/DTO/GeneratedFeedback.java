package com.callback.session.DTO;

import java.util.List;

public record GeneratedFeedback(
        String overallSummary, int clarityScore, int structureScore, int technicalDepthScore,
        List<QuestionFeedbackItem> perQuestion
) {}

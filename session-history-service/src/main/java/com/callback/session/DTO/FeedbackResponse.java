package com.callback.session.DTO;

import java.time.Instant;
import java.util.List;

public record FeedbackResponse(String overallSummary, int clarityScore, int structureScore, int technicalDepthScore,
                               Instant createdAt, List<QuestionFeedbackResponse> perQuestion) {}

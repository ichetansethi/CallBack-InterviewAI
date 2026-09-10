package com.callback.question.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuestionSetResponse(UUID id, UUID jdId, Instant createdAt, List<InterviewQuestionResponse> questions) {}

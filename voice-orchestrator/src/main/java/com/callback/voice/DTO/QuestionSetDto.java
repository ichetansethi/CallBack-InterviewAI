package com.callback.voice.DTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuestionSetDto(UUID id, UUID jdId, Instant createdAt, List<InterviewQuestionDto> questions) {}
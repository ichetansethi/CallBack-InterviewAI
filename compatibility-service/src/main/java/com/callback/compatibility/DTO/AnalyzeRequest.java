package com.callback.compatibility.DTO;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AnalyzeRequest(@NotNull UUID jdId, @NotNull UUID resumeId) {}

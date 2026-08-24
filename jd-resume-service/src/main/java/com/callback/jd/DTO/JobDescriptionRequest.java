package com.callback.jd.DTO;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public record JobDescriptionRequest(
        @NotBlank String rawText,
        @NotBlank String role,
        String company // optional, no @NotBlank
) {}


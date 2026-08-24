package com.callback.jd.DTO;

import java.time.Instant;
import java.util.UUID;

public record JobDescriptionResponse(
        UUID id, String role, String company, Instant createdAt
) {}

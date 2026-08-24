package com.callback.jd.DTO;

import java.time.Instant;
import java.util.UUID;

public record ResumeResponse(UUID id, String originalFilename, String contentType, long fileSizeBytes, Instant uploadedAt) {}

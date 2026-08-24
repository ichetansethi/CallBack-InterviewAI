package com.callback.auth.model;

import java.time.Instant;

public record RegisterResponse(Long id, String email, Instant createdAt) {
}

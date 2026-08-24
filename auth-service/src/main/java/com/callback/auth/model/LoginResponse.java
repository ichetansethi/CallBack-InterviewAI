package com.callback.auth.model;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt) {
}
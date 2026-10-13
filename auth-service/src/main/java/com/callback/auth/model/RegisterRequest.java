package com.callback.auth.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password

) {

    /** BCrypt's input limit. Spring Security's BCryptPasswordEncoder throws above it (a 500 if it got that far). */
    static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Bytes, not characters: @Size(max = 72) would still let a 40-character password of
     * two-byte characters through to the encoder.
     */
    @AssertTrue(message = "Password must be at most " + MAX_PASSWORD_BYTES + " bytes (UTF-8)")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}

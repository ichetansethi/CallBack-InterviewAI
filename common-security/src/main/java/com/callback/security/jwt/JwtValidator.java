package com.callback.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.time.Instant;

/**
 * Verifies RS256 tokens with the public key loaded by {@link RsaPublicKeyLoader}. Every service
 * that depends on common-security gets this bean for free; none of them gain the ability to sign.
 */
@Component
public class JwtValidator {

    private final PublicKey publicKey;

    public JwtValidator() {
        this(RsaPublicKeyLoader.loadFromClasspath());
    }

    /** For tests, which sign with a throwaway key pair (the real private key lives only in auth-service). */
    JwtValidator(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractEmail(String token) {
        return parse(token).getSubject();
    }

    public Instant extractExpiration(String token) {
        return parse(token).getExpiration().toInstant();
    }
}

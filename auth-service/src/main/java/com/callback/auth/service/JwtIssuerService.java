package com.callback.auth.service;

import com.callback.auth.security.RsaPrivateKeyLoader;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.util.Date;

/**
 * Signs tokens with the RSA private key. This is the only class in the whole system that ever
 * touches the private key — every other service verifies via common-security's JwtValidator
 * (public key only) and has no way to issue a token of its own.
 */
@Service
public class JwtIssuerService {

    private final PrivateKey privateKey;
    private final long expirationMs;

    public JwtIssuerService(@Value("${jwt.expiration-ms}") long expirationMs) {
        this.privateKey = RsaPrivateKeyLoader.loadFromClasspath();
        this.expirationMs = expirationMs;
    }

    public String generateToken(String subject) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}

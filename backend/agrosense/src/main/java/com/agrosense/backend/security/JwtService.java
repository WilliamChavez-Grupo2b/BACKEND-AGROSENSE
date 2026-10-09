package com.agrosense.backend.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Issues and verifies the signed tokens that authenticate API and WebSocket clients. */
@Service
public class JwtService {

    /** HS256 needs a key of at least 256 bits; a shorter secret would make tokens forgeable. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration timeToLive;

    public JwtService(@Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-minutes:60}") long expirationMinutes) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be set to a random value of at least 32 bytes");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalStateException("jwt.expiration-minutes must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.timeToLive = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(timeToLive)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** The e-mail the token was issued for, or empty when it is malformed, tampered with or expired. */
    public Optional<String> extractEmail(String token) {
        try {
            return Optional.ofNullable(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject());
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

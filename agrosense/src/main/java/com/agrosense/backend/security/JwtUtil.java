package com.agrosense.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Issues and verifies the signed tokens that authenticate API and WebSocket clients. */
@Slf4j
@Component
public class JwtUtil {

    private static final String ROLE_CLAIM = "role";

    /** HS512 needs a key of at least 512 bits; a shorter secret would make tokens forgeable. */
    private static final int MIN_SECRET_BYTES = 64;

    private final SecretKey key;
    private final Duration timeToLive;

    public JwtUtil(@Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-minutes:60}") long expirationMinutes) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be set to a random value of at least 64 bytes");
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
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(timeToLive)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /** The e-mail the token was issued for, or empty when it is malformed, tampered with or expired. */
    public Optional<String> extractEmail(String token) {
        return parseClaims(token).map(Claims::getSubject);
    }

    /** The role the token was issued with, or empty when it is malformed, tampered with or expired. */
    public Optional<String> extractRole(String token) {
        return parseClaims(token).map(claims -> claims.get(ROLE_CLAIM, String.class));
    }

    /** Whether the token carries a valid signature, has not expired and names a user. */
    public boolean isTokenValid(String token) {
        return extractEmail(token).isPresent();
    }

    private Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException exception) {
            // The reason is logged, never the token itself.
            log.debug("Token JWT rechazado: {}", exception.getMessage());
            return Optional.empty();
        }
    }
}

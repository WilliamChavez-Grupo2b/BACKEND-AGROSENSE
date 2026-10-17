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

@Slf4j
@Component
public class JwtUtil {

    private static final String ROLE_CLAIM = "role";

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

    public Optional<String> extractEmail(String token) {
        return parseClaims(token).map(Claims::getSubject);
    }

    public Optional<String> extractRole(String token) {
        return parseClaims(token).map(claims -> claims.get(ROLE_CLAIM, String.class));
    }

    public Optional<Instant> extractExpiration(String token) {
        return parseClaims(token).map(Claims::getExpiration).map(Date::toInstant);
    }

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
            log.debug("JWT rejected: {}", exception.getMessage());
            return Optional.empty();
        }
    }
}

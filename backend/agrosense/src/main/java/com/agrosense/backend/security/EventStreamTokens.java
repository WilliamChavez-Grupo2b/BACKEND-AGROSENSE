package com.agrosense.backend.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class EventStreamTokens {

    public static final String AUDIENCE = "agrosense-events";
    public static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public EventStreamTokens(@Value("${events.stream-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    EventStreamTokens(String secret, Clock clock) {
        byte[] bytes = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0 && bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("EVENTS_STREAM_SECRET must be a random value of at least 32 bytes");
        }
        this.key = bytes.length == 0 ? null : Keys.hmacShaKeyFor(bytes);
        this.clock = clock;
    }

    public Optional<String> issue(String email) {
        if (key == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return Optional.of(Jwts.builder()
                .subject(email)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TIME_TO_LIVE)))
                .signWith(key, Jwts.SIG.HS256)
                .compact());
    }
}

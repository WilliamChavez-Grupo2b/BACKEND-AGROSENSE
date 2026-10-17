package com.agrosense.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class LoginAttemptService {

    private static final int MAX_TRACKED_KEYS = 100_000;

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private final int maxAttemptsPerAccount;
    private final int maxAttemptsPerAddress;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(@Value("${login.max-attempts:5}") int maxAttemptsPerAccount,
            @Value("${login.max-attempts-per-address:20}") int maxAttemptsPerAddress,
            @Value("${login.lock-minutes:15}") long lockMinutes) {
        this(maxAttemptsPerAccount, maxAttemptsPerAddress, Duration.ofMinutes(lockMinutes), Clock.systemUTC());
    }

    public LoginAttemptService(int maxAttemptsPerAccount, int maxAttemptsPerAddress, Duration lockDuration,
            Clock clock) {
        if (maxAttemptsPerAccount < 0 || maxAttemptsPerAddress < 0 || lockDuration.isNegative()
                || lockDuration.isZero()) {
            throw new IllegalStateException("Login limits cannot be negative and the lock must last some time");
        }
        this.maxAttemptsPerAccount = maxAttemptsPerAccount;
        this.maxAttemptsPerAddress = maxAttemptsPerAddress;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public Optional<Duration> remainingLock(String email, String address) {
        Instant now = clock.instant();
        return Stream.of(accountKey(email), addressKey(address))
                .map(attempts::get)
                .filter(entry -> entry != null && entry.lockedUntil() != null && entry.lockedUntil().isAfter(now))
                .map(entry -> Duration.between(now, entry.lockedUntil()))
                .max(Duration::compareTo);
    }

    public void recordFailure(String email, String address) {
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            purgeExpired();
        }
        recordFailure(accountKey(email), maxAttemptsPerAccount);
        recordFailure(addressKey(address), maxAttemptsPerAddress);
    }

    public void recordSuccess(String email) {
        attempts.remove(accountKey(email));
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        Instant now = clock.instant();
        attempts.values().removeIf(entry -> isOver(entry, now));
    }

    private void recordFailure(String key, int limit) {
        if (limit == 0) {
            return;
        }
        Instant now = clock.instant();
        attempts.compute(key, (ignored, current) -> {
            if (current == null || isOver(current, now)) {
                current = new Attempts(0, now, null);
            }
            int failures = current.failures() + 1;
            return new Attempts(failures, current.windowStart(),
                    failures >= limit ? now.plus(lockDuration) : current.lockedUntil());
        });
    }

    private boolean isOver(Attempts entry, Instant now) {
        Instant end = entry.lockedUntil() != null ? entry.lockedUntil() : entry.windowStart().plus(lockDuration);
        return !end.isAfter(now);
    }

    private static String accountKey(String email) {
        return "account:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "address:" + (address == null ? "" : address);
    }
}

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

/**
 * Slows down password guessing: after too many failed logins for one account, or from one network
 * address, further attempts are refused until the lock expires. Counters live in memory, so they are
 * per application instance and start empty after a restart.
 */
@Component
public class LoginAttemptService {

    /** Above this many tracked keys, expired ones are dropped before a new one is added. */
    private static final int MAX_TRACKED_KEYS = 100_000;

    /** Failures seen since {@code windowStart}; {@code lockedUntil} is set once the limit is reached. */
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

    /**
     * @param maxAttemptsPerAccount failed logins allowed for one e-mail before it is locked; 0 turns it off
     * @param maxAttemptsPerAddress failed logins allowed from one address before it is locked; 0 turns it off
     */
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

    /** How much longer logins for this account or from this address stay refused; empty when allowed. */
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

    /** A successful login clears the account's failures. The address keeps its own until they expire. */
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

    /** An entry stops counting when its lock has expired or, if it never locked, when its window has. */
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

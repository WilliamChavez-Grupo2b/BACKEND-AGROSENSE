package com.agrosense.backend.security;

import com.agrosense.backend.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Events per device and minute, counted once the key is known to be right, and wrong keys on an allowance of their
 * own: a code is no secret, so anyone could otherwise spend a real device's allowance and silence it. The wrong keys
 * are counted by the code they were sent with, and those codes are invented at will: the memory they take is bounded.
 */
@Component
public class DeviceRateLimiter {

    public static final int MAX_TRACKED = 10_000;

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String WRONG_KEY = "!";

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @Autowired
    public DeviceRateLimiter(@Value("${devices.max-events-per-minute:60}") int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    public DeviceRateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public void count(String deviceCode) {
        spend(deviceCode, "");
    }

    public void countFailure(String deviceCode) {
        spend(deviceCode, WRONG_KEY);
    }

    public int tracked() {
        return recent.size();
    }

    private void spend(String deviceCode, String allowance) {
        if (perMinute <= 0 || deviceCode == null) {
            return;
        }
        Instant now = clock.instant();
        String key = allowance + deviceCode.trim().toUpperCase(Locale.ROOT);
        if (recent.size() >= MAX_TRACKED && !recent.containsKey(key)) {
            forgetIdleDevices();
            if (recent.size() >= MAX_TRACKED) {
                return;
            }
        }
        boolean[] refused = {false};
        recent.compute(key, (code, known) -> {
            Deque<Instant> events = known == null ? new ArrayDeque<>() : known;
            forgetOld(events, now);
            if (events.size() >= perMinute) {
                refused[0] = true;
            } else {
                events.addLast(now);
            }
            return events;
        });
        if (refused[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados eventos de este dispositivo; reintenta en un minuto.");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void forgetIdleDevices() {
        Instant now = clock.instant();
        for (String code : recent.keySet()) {
            recent.computeIfPresent(code, (key, events) -> {
                forgetOld(events, now);
                return events.isEmpty() ? null : events;
            });
        }
    }

    private static void forgetOld(Deque<Instant> events, Instant now) {
        while (!events.isEmpty() && !events.peekFirst().isAfter(now.minus(WINDOW))) {
            events.pollFirst();
        }
    }
}

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

/** Events per device and minute. It counts before the key is checked, so it also slows down someone guessing keys. */
@Component
public class DeviceRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

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
        if (perMinute <= 0 || deviceCode == null) {
            return;
        }
        Instant now = clock.instant();
        boolean[] refused = {false};
        recent.compute(deviceCode.trim().toUpperCase(Locale.ROOT), (code, known) -> {
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

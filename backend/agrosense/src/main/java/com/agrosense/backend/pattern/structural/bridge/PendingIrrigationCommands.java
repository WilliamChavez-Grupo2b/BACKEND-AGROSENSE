package com.agrosense.backend.pattern.structural.bridge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One order per crop, because a crop has one device. It lives in memory on purpose: an order lost in a restart means
 * no irrigation, which is safer than water started long after someone asked for it. For the same reason it expires.
 */
@Component
public class PendingIrrigationCommands {

    public static final Duration EXPIRES_AFTER = Duration.ofMinutes(5);

    private record Pending(IrrigationCommand command, Instant expiresAt) {
    }

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public PendingIrrigationCommands() {
        this(Clock.systemUTC());
    }

    public PendingIrrigationCommands(Clock clock) {
        this.clock = clock;
    }

    public void offer(IrrigationCommand command) {
        pending.put(command.cropId(), new Pending(command, clock.instant().plus(EXPIRES_AFTER)));
    }

    public Optional<IrrigationCommand> take(Integer cropId) {
        return Optional.ofNullable(pending.remove(cropId))
                .filter(order -> clock.instant().isBefore(order.expiresAt()))
                .map(Pending::command);
    }
}

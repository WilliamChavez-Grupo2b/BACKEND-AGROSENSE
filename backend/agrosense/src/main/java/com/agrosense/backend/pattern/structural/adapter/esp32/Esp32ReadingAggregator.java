package com.agrosense.backend.pattern.structural.adapter.esp32;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The ESP32 sends a block every second; storing each would fill the database and pass DEVICE_MAX_EVENTS_PER_MINUTE.
 * Each field is averaged over a window (ESP32_AGGREGATE_SECONDS, 60 by default) that starts with its first block, and
 * the window gives one reading stamped with its end. A field missing from some blocks is averaged over the others.
 */
public class Esp32ReadingAggregator {

    /** The average of a window, the moment it ended and how many blocks it held. */
    public record Window(Esp32Reading average, Instant endsAt, int blocks) {
    }

    private final Duration window;
    private final Clock clock;
    private Instant startedAt;
    private int blocks;
    private final double[] sums = new double[3];
    private final int[] counts = new int[3];

    public Esp32ReadingAggregator(Duration window, Clock clock) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("The aggregation window must be positive");
        }
        this.window = window;
        this.clock = clock;
    }

    /** Adds a block; when the window it arrives in follows a finished one, that one comes back. */
    public synchronized Optional<Window> add(Esp32Reading reading) {
        Optional<Window> closed = closeIfDue();
        if (startedAt == null) {
            startedAt = clock.instant();
        }
        blocks++;
        take(0, reading.airTemperature());
        take(1, reading.relativeHumidity());
        take(2, reading.soilMoisture());
        return closed;
    }

    /** The window, once its time is over. */
    public synchronized Optional<Window> closeIfDue() {
        if (startedAt == null || clock.instant().isBefore(startedAt.plus(window))) {
            return Optional.empty();
        }
        return close(startedAt.plus(window));
    }

    /** What the window holds so far, stamped now: the connection dropped before it ended. */
    public synchronized Optional<Window> flush() {
        if (startedAt == null) {
            return Optional.empty();
        }
        Instant end = startedAt.plus(window);
        Instant now = clock.instant();
        return close(now.isBefore(end) ? now : end);
    }

    private void take(int field, Double value) {
        if (value != null) {
            sums[field] += value;
            counts[field]++;
        }
    }

    private Optional<Window> close(Instant end) {
        Esp32Reading average = new Esp32Reading(mean(0), mean(1), mean(2));
        int held = blocks;
        startedAt = null;
        blocks = 0;
        for (int field = 0; field < sums.length; field++) {
            sums[field] = 0;
            counts[field] = 0;
        }
        return average.isEmpty() ? Optional.empty() : Optional.of(new Window(average, end, held));
    }

    private Double mean(int field) {
        if (counts[field] == 0) {
            return null;
        }
        return BigDecimal.valueOf(sums[field] / counts[field]).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

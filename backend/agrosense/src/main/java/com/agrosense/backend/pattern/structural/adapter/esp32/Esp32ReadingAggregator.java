package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.pattern.behavioral.iterator.BlockWindow;
import com.agrosense.backend.pattern.behavioral.strategy.AggregationStrategy;
import com.agrosense.backend.pattern.behavioral.strategy.MeanAggregation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The ESP32 sends a block every second; storing each would fill the database and pass DEVICE_MAX_EVENTS_PER_MINUTE.
 * Each field is combined over a window (ESP32_AGGREGATE_SECONDS, 60 by default) that starts with its first block, and
 * the window gives one reading stamped with its end. A field missing from some blocks is combined over the others.
 *
 * <p>The blocks are kept in a {@link BlockWindow} and walked with its iterator; how they are combined is an
 * {@link AggregationStrategy}: the mean by default, or the median (ESP32_AGGREGATE_STRATEGY).
 */
public class Esp32ReadingAggregator {

    /** The combined reading of a window, the moment it ended and how many blocks it held. */
    public record Window(Esp32Reading average, Instant endsAt, int blocks) {
    }

    private final Duration window;
    private final Clock clock;
    private final AggregationStrategy strategy;
    private final BlockWindow blocks = new BlockWindow();
    private Instant startedAt;

    public Esp32ReadingAggregator(Duration window, Clock clock) {
        this(window, clock, new MeanAggregation());
    }

    public Esp32ReadingAggregator(Duration window, Clock clock, AggregationStrategy strategy) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("The aggregation window must be positive");
        }
        this.window = window;
        this.clock = clock;
        this.strategy = strategy;
    }

    public AggregationStrategy strategy() {
        return strategy;
    }

    /** Adds a block; when the window it arrives in follows a finished one, that one comes back. */
    public synchronized Optional<Window> add(Esp32Reading reading) {
        Optional<Window> closed = closeIfDue();
        if (startedAt == null) {
            startedAt = clock.instant();
        }
        blocks.add(reading);
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

    private Optional<Window> close(Instant end) {
        Esp32Reading combined = new Esp32Reading(
                strategy.combine(blocks.values(Esp32Reading::airTemperature)),
                strategy.combine(blocks.values(Esp32Reading::relativeHumidity)),
                strategy.combine(blocks.values(Esp32Reading::soilMoisture)));
        int held = blocks.size();
        startedAt = null;
        blocks.clear();
        return combined.isEmpty() ? Optional.empty() : Optional.of(new Window(combined, end, held));
    }
}

package com.agrosense.backend;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator.Window;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Esp32ReadingAggregatorTests {

    private static final Instant START = Instant.parse("2026-11-10T14:00:00Z");

    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        private Instant now = START;

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Hand clock = new Hand();
    private final Esp32ReadingAggregator aggregator = new Esp32ReadingAggregator(Duration.ofSeconds(60), clock);

    @Test
    void sixtyBlocksInSixtySecondsGiveOneReadingWithTheAverages() {
        List<Window> windows = new ArrayList<>();
        for (int second = 0; second < 60; second++) {
            // 20.0 to 25.9, 50 to 109 (only the first 51 are humidities a sensor gives, but any number averages),
            // and a soil value only every other second.
            Esp32Reading block = new Esp32Reading(20 + second / 10.0, 50.0 + second % 2, second % 2 == 0 ? 30.0 : null);
            aggregator.add(block).ifPresent(windows::add);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(windows).isEmpty();  // the 60th block arrived at 59 s: the window is still open

        aggregator.closeIfDue().ifPresent(windows::add);

        assertThat(windows).hasSize(1);
        Window window = windows.get(0);
        assertThat(window.blocks()).isEqualTo(60);
        assertThat(window.endsAt()).isEqualTo(START.plusSeconds(60));
        assertThat(window.average().airTemperature()).isEqualTo(22.95);  // mean of 20.0 .. 25.9
        assertThat(window.average().relativeHumidity()).isEqualTo(50.5);
        assertThat(window.average().soilMoisture()).isEqualTo(30.0);  // over the 30 blocks that had it
        assertThat(aggregator.closeIfDue()).isEmpty();
    }

    @Test
    void theBlockAfterTheWindowClosesItAndStartsTheNextOne() {
        aggregator.add(new Esp32Reading(24.0, null, null));
        clock.advance(Duration.ofSeconds(61));

        assertThat(aggregator.add(new Esp32Reading(26.0, null, null)))
                .hasValueSatisfying(window -> assertThat(window.average().airTemperature()).isEqualTo(24.0));
        clock.advance(Duration.ofSeconds(60));
        assertThat(aggregator.closeIfDue())
                .hasValueSatisfying(window -> assertThat(window.average().airTemperature()).isEqualTo(26.0));
    }

    @Test
    void aDroppedConnectionFlushesWhatTheWindowHadStampedNow() {
        aggregator.add(new Esp32Reading(null, 61.0, 31.0));
        clock.advance(Duration.ofSeconds(20));

        assertThat(aggregator.flush()).hasValueSatisfying(window -> {
            assertThat(window.endsAt()).isEqualTo(START.plusSeconds(20));
            assertThat(window.average()).isEqualTo(new Esp32Reading(null, 61.0, 31.0));
        });
        assertThat(aggregator.flush()).isEmpty();
    }
}

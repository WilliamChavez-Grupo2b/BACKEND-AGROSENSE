package com.agrosense.backend.pattern.behavioral.chain;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.flyweight.Esp32ReadingPool;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * The block the handlers fill in, from its header to its blank line. Not thread-safe: one connection feeds it. The
 * reading it gives comes from an Esp32ReadingPool (a Flyweight), so equal blocks are one shared object.
 */
@Slf4j
public class BlockInProgress {

    private final Esp32ReadingPool pool;
    private boolean open;
    private Double airTemperature;
    private Double relativeHumidity;
    private Double soilMoisture;
    private long discarded;

    public BlockInProgress() {
        this(new Esp32ReadingPool());
    }

    public BlockInProgress(Esp32ReadingPool pool) {
        this.pool = pool;
    }

    public Esp32ReadingPool pool() {
        return pool;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        open = true;
    }

    public void airTemperature(Double value) {
        airTemperature = value;
    }

    public void relativeHumidity(Double value) {
        relativeHumidity = value;
    }

    public void soilMoisture(Double value) {
        soilMoisture = value;
    }

    /** Ends the block: its reading when at least one field is usable. A block with none is counted and dropped. */
    public Optional<Esp32Reading> close() {
        if (!open) {
            return Optional.empty();
        }
        boolean empty = airTemperature == null && relativeHumidity == null && soilMoisture == null;
        Esp32Reading reading = empty ? null : pool.of(airTemperature, relativeHumidity, soilMoisture);
        open = false;
        airTemperature = null;
        relativeHumidity = null;
        soilMoisture = null;
        if (empty) {
            discarded++;
            log.warn("ESP32: block without any usable value dropped");
            return Optional.empty();
        }
        return Optional.of(reading);
    }

    public long discarded() {
        return discarded;
    }
}

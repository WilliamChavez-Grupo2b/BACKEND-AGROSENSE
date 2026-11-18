package com.agrosense.backend.pattern.behavioral.chain;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/** The block the handlers fill in, from its header to its blank line. Not thread-safe: one connection feeds it. */
@Slf4j
public class BlockInProgress {

    private boolean open;
    private Double airTemperature;
    private Double relativeHumidity;
    private Double soilMoisture;
    private long discarded;

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
        Esp32Reading reading = new Esp32Reading(airTemperature, relativeHumidity, soilMoisture);
        open = false;
        airTemperature = null;
        relativeHumidity = null;
        soilMoisture = null;
        if (reading.isEmpty()) {
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

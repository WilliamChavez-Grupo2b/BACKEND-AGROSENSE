package com.agrosense.backend.pattern.structural.adapter.esp32;

/** One reading of the ESP32, from one block or averaged over a window. A field it could not measure is null. */
public record Esp32Reading(Double airTemperature, Double relativeHumidity, Double soilMoisture) {

    public boolean isEmpty() {
        return airTemperature == null && relativeHumidity == null && soilMoisture == null;
    }
}

package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure;

import java.util.ArrayList;
import java.util.List;

/** One reading of the ESP32, from one block or averaged over a window. A field it could not measure is null. */
public record Esp32Reading(Double airTemperature, Double relativeHumidity, Double soilMoisture) {

    public boolean isEmpty() {
        return airTemperature == null && relativeHumidity == null && soilMoisture == null;
    }

    /** The fields it measured, as the elements a visitor walks: air temperature, air humidity, then soil. */
    public List<Esp32Measure> measures() {
        List<Esp32Measure> measures = new ArrayList<>(3);
        if (airTemperature != null) {
            measures.add(new Esp32Measure.AirTemperature(airTemperature));
        }
        if (relativeHumidity != null) {
            measures.add(new Esp32Measure.RelativeHumidity(relativeHumidity));
        }
        if (soilMoisture != null) {
            measures.add(new Esp32Measure.SoilMoisture(soilMoisture));
        }
        return measures;
    }
}

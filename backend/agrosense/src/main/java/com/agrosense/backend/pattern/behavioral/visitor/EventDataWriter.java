package com.agrosense.backend.pattern.behavioral.visitor;

import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.AirTemperature;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.RelativeHumidity;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.SoilMoisture;
import tools.jackson.databind.node.ObjectNode;

/** Writes each measure into the "data" of the event POST /api/internal/events receives, under its own name. */
public class EventDataWriter implements Esp32MeasureVisitor<ObjectNode> {

    private final ObjectNode data;

    public EventDataWriter(ObjectNode data) {
        this.data = data;
    }

    @Override
    public ObjectNode visitAirTemperature(AirTemperature measure) {
        return data.put("air_temperature", measure.value());
    }

    @Override
    public ObjectNode visitRelativeHumidity(RelativeHumidity measure) {
        return data.put("relative_humidity", measure.value());
    }

    @Override
    public ObjectNode visitSoilMoisture(SoilMoisture measure) {
        return data.put("soil_moisture", measure.value());
    }
}

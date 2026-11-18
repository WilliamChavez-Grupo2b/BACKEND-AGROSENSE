package com.agrosense.backend.pattern.behavioral.visitor;

import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.AirTemperature;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.RelativeHumidity;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.SoilMoisture;

/** Visitor: one operation over the ESP32's measures, with one method per kind of measure. */
public interface Esp32MeasureVisitor<R> {

    R visitAirTemperature(AirTemperature measure);

    R visitRelativeHumidity(RelativeHumidity measure);

    R visitSoilMoisture(SoilMoisture measure);
}

package com.agrosense.backend.pattern.behavioral.visitor;

/**
 * Visitor, the elements: the three things the ESP32 measures. Each one sends the visitor to its own method, so an
 * operation over the measures (writing the event, describing it) is a new visitor, never a type check.
 */
public sealed interface Esp32Measure {

    double value();

    <R> R accept(Esp32MeasureVisitor<R> visitor);

    record AirTemperature(double value) implements Esp32Measure {
        @Override
        public <R> R accept(Esp32MeasureVisitor<R> visitor) {
            return visitor.visitAirTemperature(this);
        }
    }

    record RelativeHumidity(double value) implements Esp32Measure {
        @Override
        public <R> R accept(Esp32MeasureVisitor<R> visitor) {
            return visitor.visitRelativeHumidity(this);
        }
    }

    record SoilMoisture(double value) implements Esp32Measure {
        @Override
        public <R> R accept(Esp32MeasureVisitor<R> visitor) {
            return visitor.visitSoilMoisture(this);
        }
    }
}

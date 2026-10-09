package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import lombok.RequiredArgsConstructor;

import java.math.RoundingMode;

/** Rounds the value to two decimals and fills in the unit when the device did not send one. */
@RequiredArgsConstructor
public class NormalizationDecorator implements SensorReadingProcessor {

    private static final int VALUE_SCALE = 2;

    private final SensorReadingProcessor delegate;

    @Override
    public SensorReading process(SensorReading reading) {
        SensorReading result = delegate.process(reading);
        if (result.getValue() != null) {
            result.setValue(result.getValue().setScale(VALUE_SCALE, RoundingMode.HALF_UP));
        }
        if (result.getUnit() == null || result.getUnit().isBlank()) {
            result.setUnit(SensorFactory.defaultUnit(result.getSensor().getSensorType()));
        }
        return result;
    }
}

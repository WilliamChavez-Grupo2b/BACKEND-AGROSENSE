package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;

/** Innermost processor: returns the reading unchanged. The decorators add behaviour around it. */
public class BaseSensorReadingProcessor implements SensorReadingProcessor {

    @Override
    public SensorReading process(SensorReading reading) {
        return reading;
    }
}

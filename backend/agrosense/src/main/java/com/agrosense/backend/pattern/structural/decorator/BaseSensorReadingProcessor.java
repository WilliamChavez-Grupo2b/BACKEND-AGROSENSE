package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;

public class BaseSensorReadingProcessor implements SensorReadingProcessor {

    @Override
    public SensorReading process(SensorReading reading) {
        return reading;
    }
}

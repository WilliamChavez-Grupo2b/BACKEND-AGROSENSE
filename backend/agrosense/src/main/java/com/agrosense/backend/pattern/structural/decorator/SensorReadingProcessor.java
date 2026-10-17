package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;

public interface SensorReadingProcessor {

    SensorReading process(SensorReading reading);
}

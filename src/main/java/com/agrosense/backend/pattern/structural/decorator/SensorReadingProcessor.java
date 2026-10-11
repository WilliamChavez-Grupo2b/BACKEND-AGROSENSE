package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;

/** Decorator pattern: the operation every processing step implements. */
public interface SensorReadingProcessor {

    SensorReading process(SensorReading reading);
}

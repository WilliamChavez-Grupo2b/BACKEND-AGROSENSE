package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CalibrationDecorator implements SensorReadingProcessor {

    private final SensorReadingProcessor delegate;

    @Override
    public SensorReading process(SensorReading reading) {
        SensorReading result = delegate.process(reading);
        if (result.getValue() != null) {
            result.setValue(CalibrationRegistry.INSTANCE.of(result.getSensor().getSensorType())
                    .apply(result.getValue()));
        }
        return result;
    }
}

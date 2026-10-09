package com.agrosense.backend.pattern.structural.decorator;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.SensorReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Marks readings whose value is physically impossible for the sensor type. */
@Slf4j
@RequiredArgsConstructor
public class ValidationDecorator implements SensorReadingProcessor {

    public static final String QUALITY_OK = "OK";
    public static final String QUALITY_OUT_OF_RANGE = "OUT_OF_RANGE";

    private final SensorReadingProcessor delegate;

    @Override
    public SensorReading process(SensorReading reading) {
        SensorReading result = delegate.process(reading);
        if (!isPlausible(result)) {
            result.setQuality(QUALITY_OUT_OF_RANGE);
            log.warn("Lectura fuera de rango: sensor={} valor={}",
                    result.getSensor().getSensorCode(), result.getValue());
        }
        return result;
    }

    private static boolean isPlausible(SensorReading reading) {
        if (reading.getValue() == null) {
            return false;
        }
        double value = reading.getValue().doubleValue();
        SensorType type = reading.getSensor().getSensorType();
        return switch (type) {
            case SOIL_MOISTURE, RELATIVE_HUMIDITY -> value >= 0 && value <= 100;
            case AIR_TEMPERATURE, SOIL_TEMPERATURE -> value >= -40 && value <= 80;
            case PH -> value >= 0 && value <= 14;
            case CONDUCTIVITY -> value >= 0 && value <= 100;
            case LIGHT -> value >= 0 && value <= 200_000;
            case RAIN_GAUGE -> value >= 0 && value <= 500;
        };
    }
}

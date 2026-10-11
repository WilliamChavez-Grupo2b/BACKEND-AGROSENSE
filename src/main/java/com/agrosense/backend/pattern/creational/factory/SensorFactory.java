package com.agrosense.backend.pattern.creational.factory;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Factory pattern: single place that knows how a sensor of each type is created and which unit its
 * readings use when the device does not send one.
 */
@Component
public class SensorFactory {

    public Sensor create(SensorType type, String sensorCode, String location, Crop crop) {
        if (type == null || crop == null || sensorCode == null || sensorCode.isBlank()) {
            throw new IllegalArgumentException("A sensor needs a type, a code and a crop");
        }
        return Sensor.builder()
                .sensorCode(sensorCode.trim().toUpperCase(Locale.ROOT))
                .sensorType(type)
                .location(location == null || location.isBlank() ? null : location.trim())
                .crop(crop)
                .active(true)
                .build();
    }

    /** Unit a sensor of this type reports in. */
    public static String defaultUnit(SensorType type) {
        return switch (type) {
            case SOIL_MOISTURE, RELATIVE_HUMIDITY -> "%";
            case AIR_TEMPERATURE, SOIL_TEMPERATURE -> "°C";
            case PH -> "pH";
            case CONDUCTIVITY -> "dS/m";
            case LIGHT -> "lux";
            case RAIN_GAUGE -> "mm";
        };
    }
}

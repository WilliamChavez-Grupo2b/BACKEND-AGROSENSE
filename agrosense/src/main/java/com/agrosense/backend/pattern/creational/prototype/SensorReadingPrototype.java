package com.agrosense.backend.pattern.creational.prototype;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Prototype pattern: keeps one template reading per sensor type and creates new readings by copying it,
 * so every reading of a type starts with the same unit and quality.
 */
@Component
public class SensorReadingPrototype {

    private static final String DEFAULT_QUALITY = "OK";

    private final Map<SensorType, SensorReading> prototypes = new EnumMap<>(SensorType.class);

    public SensorReadingPrototype() {
        for (SensorType type : SensorType.values()) {
            prototypes.put(type, SensorReading.builder()
                    .value(BigDecimal.ZERO)
                    .unit(SensorFactory.defaultUnit(type))
                    .quality(DEFAULT_QUALITY)
                    .build());
        }
    }

    /** Copies the template of the sensor's type and fills in the sensor, the value and the current time. */
    public SensorReading cloneFor(Sensor sensor, BigDecimal value) {
        SensorReading template = prototypes.get(sensor.getSensorType());
        return SensorReading.builder()
                .sensor(sensor)
                .value(value)
                .unit(template.getUnit())
                .quality(template.getQuality())
                .recordedAt(LocalDateTime.now())
                .build();
    }
}

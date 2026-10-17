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

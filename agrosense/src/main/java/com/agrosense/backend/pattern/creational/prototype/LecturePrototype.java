package com.agrosense.backend.pattern.creational.prototype;

import com.agrosense.backend.domain.models.LectureSensor;
import com.agrosense.backend.domain.models.Sensor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;


@Component
public class LecturaPrototype {

    // Registro de prototypes por tipo de sensor
    private final Map<String, LectureSensor> prototypes = new HashMap<>();

    /**
     * Registra una lecture como prototipo reutilizable.
     */
    public void registPrototype(String Key,
                                   LectureSensor lecture) {
        prototypes.put(Key, lecture);
    }

    /**
     * Clona el prototipo y aplica el nuevo value y time.
     */
    public LectureSensor clone(String Key,
                                BigDecimal newValue,
                                Sensor sensor) {
        LectureSensor base = prototypes.get(Key);

        // Si no hay prototipo registrado, crear uno nuevo
        if (base == null) {
            return LectureSensor.builder()
                .sensor(sensor)
                .value(newValue)
                .time(LocalDateTime.now())
                .quality("OK")
                .build();
        }

        // Clonar el prototipo con el nuevo value
        return LectureSensor.builder()
            .sensor(base.getSensor() != null
                ? base.getSensor() : sensor)
            .value(newValue)
            .unity(base.getUnity())
            .quality(base.getQuality())
            .time(LocalDateTime.now())
            .build();
    }

    /**
     * Precarga prototypes para los tipos de sensor más comunes.
     */
    public void StartPrototypes() {
        LectureSensor humidityBase = LectureSensor.builder()
            .value(BigDecimal.ZERO)
            .unity("%")
            .quality("OK")
            .build();

        LectureSensor temperatureBase = LectureSensor.builder()
            .value(BigDecimal.ZERO)
            .unity("°C")
            .quality("OK")
            .build();

        LectureSensor phBase = LectureSensor.builder()
            .value(BigDecimal.ZERO)
            .unity("pH")
            .quality("OK")
            .build();

        prototypes.put("SOIL_MOISTURE",   humidityBase);
        prototypes.put("AIR_TEMPERATURE", temperaturaBase);
        prototypes.put("PH",              phBase);
    }
}
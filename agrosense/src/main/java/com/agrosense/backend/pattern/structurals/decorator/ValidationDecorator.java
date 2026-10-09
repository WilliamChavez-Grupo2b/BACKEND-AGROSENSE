package com.agrosense.backend.pattern.estructural.decorator;

import com.agrosense.backend.domain.model.LectureSensor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class ValidacionDecorator implements LecturaProcessor {

    private final LectureProcessorBase base;

    @Override
    public LectureSensor procesar(LectureSensor lecture) {
        // Primero ejecuta el procesador base
        LectureSensor result = base.procesar(lecture);

        // Luego añade la validación
        if (!isValid(result)) {
            result.setQuality("OUT_RANGER");
            log.warn("Lecture out of ranger: sensor={} value={}",
                result.getSensor().getSensorCode(),
                result.getValue());
        }
        return result;
    }

    private boolean isValid(LectureSensor lecture) {
        if (lecture.getValue() == null) return false;
        double value = lecture.getValue().doubleValue();
        String type  = lecture.getSensor()
                             .getSensorType().name();
        return switch (type) {
            case "SOIL_MOISTURE",
                 "SOIL_RELATIVE" -> value >= 0 && value <= 100;
            case "AIR_TEMPERATURE",
                 "TEMPERATURE_MOISTURE" -> value >= -40 && value <= 80;
            case "PH"              -> value >= 0  && value <= 14;
            case "CONDUCTIVITY"   -> value >= 0  && value <= 10000;
            case "BRIGHTNESS"     -> value >= 0  && value <= 200000;
            case "PLUVIOMETER"     -> value >= 0  && value <= 500;
            default                -> true;
        };
    }
}
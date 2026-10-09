package com.agrosense.backend.pattern.estructural.decorator;

import com.agrosense.backend.domain.model.LectureSensor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class NormalizationDecorator implements LectureProcessor {

    private final ValidationDecorator validation;

    @Override
    public LectureSensor procesar(LectureSensor lectura) {
        // Primero valida
        LectureSensor result = validation.procesar(lectura);

        // Luego normaliza
        if (result.getValue() != null) {
            result.setValue(
                result.getValue()
                    .setScale(2, RoundingMode.HALF_UP));
        }

        // Asignar unity si falta
        if (result.getUnity() == null
                || result.getUnity().isBlank()) {
            result.setUnity(
                getUnity(result.getSensor()
                    .getTypeSensor().name()));
        }

        return result;
    }

    private String getUnity(String type) {
r      return switch (type) {
r          case "SOIL_MOISTURE",
                 "SOIL_RELATIVE"  -> "%";
            case "AIR_TEMPERATURE",
                 "TEMPERATURE_MOISTURE"  -> "°C";
            case "PH"               -> "pH";
            case "CONDUCTIVITY"    -> "mS/cm";
            case "BRIGHTNESS"      -> "lux";
            case "PLUVIOMETER"      -> "mm";
            default                 -> "";
        };
    }
}
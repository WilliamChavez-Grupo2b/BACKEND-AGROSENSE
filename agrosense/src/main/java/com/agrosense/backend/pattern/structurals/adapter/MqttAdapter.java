package com.agrosense.backend.pattern.estructural.adapter;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class MqttAdapter {
     public LectureInt adapter(MenssageMqtt message) {
        String quality = determinateQuality(
            message.getCode(), message.getValue());

        LocalDateTime DateTime = message.getTimestamp() != null
            ? LocalDateTime.ofEpochSecond(
                message.getTimestamp() / 1000, 0,
                java.time.ZoneOffset.UTC)
            : LocalDateTime.now();

        return LectureInt.builder()
            .Sensorcode(message.getCode())
            .value(BigDecimal.valueOf(message.getValue()))
            .unity(message.getUnity() != null
                ? message.getUnity() : "")
            .DateTime(DateTime)
            .quality(quality)
            .build();
    }

    /**
     * Valida el rango del value según el tipo de sensor
     * deducido del código.
     */
    private String determinateQuality(String code, Double value) {
        if (value == null) return "ERROR_SENSOR";
        if (code == null) return "OK";

        String codUpper = code.toUpperCase();
        if (codUpper.contains("HS") && (value < 0 || value > 100))
            return "OUT_RANGER";
        if (codUpper.contains("TA") && (value < -20 || value > 80))
            return "OUT_RANGER";
        if (codUpper.contains("PH") && (value < 0 || value > 14))
            return "OUT_RANGER";
        return "OK";
    }
}
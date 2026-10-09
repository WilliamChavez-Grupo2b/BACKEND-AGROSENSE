package com.agrosense.backend.pattern.structural.adapter;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Adapter pattern: converts the device format ({@link MessageMqtt}) into the format the application
 * works with ({@link SensorReadingData}). Range checks are not done here; the decorators do them once
 * the sensor type is known.
 */
@Component
public class MqttAdapter {

    private static final int MAX_CODE_LENGTH = 255;
    private static final int MAX_UNIT_LENGTH = 20;
    /** The readings column holds eight integer digits. */
    private static final double MAX_ABSOLUTE_VALUE = 99_999_999;
    /** Clocks on field devices drift; anything further ahead than this is treated as "now". */
    private static final long MAX_FUTURE_SKEW_MINUTES = 5;

    /**
     * @throws IllegalArgumentException when the message has no usable sensor code or value
     */
    public SensorReadingData adapt(MessageMqtt message) {
        if (message == null || message.getCode() == null || message.getCode().isBlank()
                || message.getCode().length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("MQTT message without a valid sensor code");
        }
        if (message.getValue() == null || !Double.isFinite(message.getValue())) {
            throw new IllegalArgumentException("MQTT message without a finite value");
        }
        if (Math.abs(message.getValue()) > MAX_ABSOLUTE_VALUE) {
            throw new IllegalArgumentException("MQTT message with a value too large to store");
        }
        return SensorReadingData.builder()
                .sensorCode(message.getCode().trim().toUpperCase(Locale.ROOT))
                .value(BigDecimal.valueOf(message.getValue()))
                .unit(cleanUnit(message.getUnit()))
                .recordedAt(toLocalDateTime(message.getTimestamp()))
                .build();
    }

    private static String cleanUnit(String unit) {
        if (unit == null || unit.isBlank() || unit.length() > MAX_UNIT_LENGTH) {
            return null;
        }
        return unit.trim();
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        LocalDateTime now = LocalDateTime.now();
        if (epochMillis == null || epochMillis <= 0) {
            return now;
        }
        LocalDateTime reported = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return reported.isAfter(now.plusMinutes(MAX_FUTURE_SKEW_MINUTES)) ? now : reported;
    }
}

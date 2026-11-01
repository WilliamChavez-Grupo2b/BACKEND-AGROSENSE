package com.agrosense.backend.pattern.creational.builder;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;

import java.math.BigDecimal;

public final class AlertBuilder {

    private Crop crop;
    private Sensor sensor;
    private AlertType alertType;
    private Severity severity = Severity.MEDIUM;
    private String message;
    private BigDecimal detectedValue;

    private AlertBuilder() {
    }

    public static AlertBuilder forCrop(Crop crop) {
        AlertBuilder builder = new AlertBuilder();
        builder.crop = crop;
        return builder;
    }

    public AlertBuilder sensor(Sensor sensor) {
        this.sensor = sensor;
        return this;
    }

    public AlertBuilder type(AlertType alertType) {
        this.alertType = alertType;
        return this;
    }

    public AlertBuilder severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public AlertBuilder message(String message) {
        this.message = message;
        return this;
    }

    public AlertBuilder detectedValue(BigDecimal detectedValue) {
        this.detectedValue = detectedValue;
        return this;
    }

    public AlertBuilder automaticSeverity() {
        if (alertType == null) {
            return this;
        }
        double value = detectedValue == null ? Double.NaN : detectedValue.doubleValue();
        this.severity = switch (alertType) {
            case LOW_HUMIDITY -> value < 20 ? Severity.VERY_HIGH : value < 30 ? Severity.HIGH : Severity.MEDIUM;
            case HIGH_TEMPERATURE -> value > 45 ? Severity.VERY_HIGH : value > 40 ? Severity.HIGH : Severity.MEDIUM;
            case PH_OUT_OF_RANGE -> Severity.HIGH;
            case PEST_DETECTED, FROST_RISK -> Severity.VERY_HIGH;
            case SENSOR_OFFLINE, SENSOR_FAULT -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
        return this;
    }

    public Alert build() {
        if (crop == null) {
            throw new IllegalStateException("An alert needs a crop");
        }
        if (alertType == null) {
            throw new IllegalStateException("An alert needs a type");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalStateException("An alert needs a message");
        }
        return Alert.builder()
                .crop(crop)
                .sensor(sensor)
                .alertType(alertType)
                .severity(severity)
                .message(message)
                .detectedValue(detectedValue)
                .acknowledged(false)
                .build();
    }
}

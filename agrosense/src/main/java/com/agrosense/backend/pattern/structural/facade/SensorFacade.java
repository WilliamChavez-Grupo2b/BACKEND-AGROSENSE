package com.agrosense.backend.pattern.structural.facade;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.event.AlertRaisedEvent;
import com.agrosense.backend.event.SensorReadingRecordedEvent;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;
import com.agrosense.backend.pattern.creational.prototype.SensorReadingPrototype;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.decorator.SensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Facade pattern: one entry point for "a reading arrived", used by both MQTT and REST. It hides the
 * steps behind it: find the sensor, build and process the reading, store it, raise an alert when the
 * value leaves the crop's range, and announce what happened.
 */
@Component
@RequiredArgsConstructor
public class SensorFacade {

    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final SensorReadingPrototype readingPrototype;
    private final SensorReadingProcessor readingProcessor;
    private final ApplicationEventPublisher events;

    @Transactional
    public SensorReading recordReading(SensorReadingData data) {
        Sensor sensor = sensorRepository.findBySensorCode(data.getSensorCode())
                .orElseThrow(() -> new ResourceNotFoundException("Sensor not found: " + data.getSensorCode()));
        if (!Boolean.TRUE.equals(sensor.getActive())) {
            throw new BusinessRuleException("El sensor está inactivo y no puede registrar lecturas.");
        }

        SensorReading reading = readingPrototype.cloneFor(sensor, data.getValue());
        if (data.getUnit() != null) {
            reading.setUnit(data.getUnit());
        }
        if (data.getRecordedAt() != null) {
            reading.setRecordedAt(data.getRecordedAt());
        }
        reading = readingRepository.save(readingProcessor.process(reading));
        sensor.setLastReadingAt(reading.getRecordedAt());

        String ownerEmail = sensor.getCrop().getEstate().getUser().getEmail();
        events.publishEvent(new SensorReadingRecordedEvent(ownerEmail, SensorReadingResponse.from(reading)));

        // An impossible value says the sensor is faulty, not that the crop is in trouble.
        if (ValidationDecorator.QUALITY_OK.equals(reading.getQuality())) {
            raiseAlertIfOutOfCropRange(sensor, reading, ownerEmail);
        }
        return reading;
    }

    private void raiseAlertIfOutOfCropRange(Sensor sensor, SensorReading reading, String ownerEmail) {
        Crop crop = sensor.getCrop();
        BigDecimal value = reading.getValue();
        AlertType type = switch (sensor.getSensorType()) {
            case SOIL_MOISTURE, RELATIVE_HUMIDITY -> below(value, crop.getHumidityMin()) ? AlertType.LOW_HUMIDITY
                    : above(value, crop.getHumidityMax()) ? AlertType.HIGH_HUMIDITY : null;
            case AIR_TEMPERATURE, SOIL_TEMPERATURE -> below(value, crop.getTempMin()) ? AlertType.LOW_TEMPERATURE
                    : above(value, crop.getTempMax()) ? AlertType.HIGH_TEMPERATURE : null;
            case PH -> below(value, crop.getPhMin()) || above(value, crop.getPhMax())
                    ? AlertType.PH_OUT_OF_RANGE : null;
            default -> null;
        };
        // One open alert per crop and type is enough; more would only repeat the same warning.
        if (type == null
                || alertRepository.existsByCropIdCropAndAlertTypeAndAcknowledgedFalse(crop.getIdCrop(), type)) {
            return;
        }
        Alert alert = alertRepository.save(AlertBuilder.forCrop(crop)
                .sensor(sensor)
                .type(type)
                .detectedValue(value)
                .message(describe(type, reading))
                .automaticSeverity()
                .build());
        events.publishEvent(new AlertRaisedEvent(ownerEmail, AlertResponse.from(alert)));
    }

    private static String describe(AlertType type, SensorReading reading) {
        String measured = reading.getValue().toPlainString() + " " + reading.getUnit();
        return switch (type) {
            case LOW_HUMIDITY -> "La humedad está por debajo del mínimo del cultivo (" + measured + ").";
            case HIGH_HUMIDITY -> "La humedad está por encima del máximo del cultivo (" + measured + ").";
            case LOW_TEMPERATURE -> "La temperatura está por debajo del mínimo del cultivo (" + measured + ").";
            case HIGH_TEMPERATURE -> "La temperatura está por encima del máximo del cultivo (" + measured + ").";
            case PH_OUT_OF_RANGE -> "El pH está fuera del rango del cultivo (" + measured + ").";
            default -> "Lectura fuera del rango del cultivo (" + measured + ").";
        };
    }

    private static boolean below(BigDecimal value, BigDecimal minimum) {
        return minimum != null && value.compareTo(minimum) < 0;
    }

    private static boolean above(BigDecimal value, BigDecimal maximum) {
        return maximum != null && value.compareTo(maximum) > 0;
    }
}

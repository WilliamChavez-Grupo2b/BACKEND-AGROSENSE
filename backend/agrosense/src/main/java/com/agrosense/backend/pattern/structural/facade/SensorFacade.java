package com.agrosense.backend.pattern.structural.facade;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.event.AlertRaisedEvent;
import com.agrosense.backend.event.SensorReadingRecordedEvent;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;
import com.agrosense.backend.pattern.creational.prototype.SensorReadingPrototype;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.decorator.SensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SensorFacade {

    private static final int SUMMARY_READINGS = 10;
    private static final int SUMMARY_ALERTS = 5;

    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AiPredictionRepository predictionRepository;
    private final CropRepository cropRepository;
    private final SensorReadingPrototype readingPrototype;
    private final SensorReadingProcessor readingProcessor;
    private final ApplicationEventPublisher events;

    public record Recorded(SensorReading reading, Optional<Alert> alert) {
    }

    @Transactional
    public SensorReading recordReading(SensorReadingData data) {
        return record(data).reading();
    }

    @Transactional
    public Recorded record(SensorReadingData data) {
        Sensor sensor = sensorRepository.findBySensorCode(data.getSensorCode())
                .orElseThrow(() -> new ResourceNotFoundException("Sensor not found: " + data.getSensorCode()));
        if (!Boolean.TRUE.equals(sensor.getActive())) {
            throw new BusinessRuleException("El sensor está inactivo y no puede registrar lecturas.");
        }
        if (!Boolean.TRUE.equals(sensor.getCrop().getActive())
                || !Boolean.TRUE.equals(sensor.getCrop().getEstate().getUser().getActive())) {
            throw new BusinessRuleException("El sensor pertenece a un cultivo o a una cuenta inactivos.");
        }

        SensorReading reading = readingPrototype.cloneFor(sensor, data.getValue());
        if (data.getUnit() != null) {
            reading.setUnit(data.getUnit());
        }
        if (data.getRecordedAt() != null) {
            reading.setRecordedAt(data.getRecordedAt());
        }
        reading = readingRepository.save(readingProcessor.process(reading));
        if (sensor.getLastReadingAt() == null || reading.getRecordedAt().isAfter(sensor.getLastReadingAt())) {
            sensor.setLastReadingAt(reading.getRecordedAt());
        }

        String ownerEmail = sensor.getCrop().getEstate().getUser().getEmail();
        events.publishEvent(new SensorReadingRecordedEvent(ownerEmail, SensorReadingResponse.from(reading)));

        Optional<Alert> alert = ValidationDecorator.QUALITY_OK.equals(reading.getQuality())
                ? raiseAlertIfOutOfCropRange(sensor, reading, ownerEmail)
                : Optional.empty();
        return new Recorded(reading, alert);
    }

    @Transactional
    public Optional<Alert> raiseAlert(Crop crop, AlertType type, String message, Severity severity,
            BigDecimal detectedValue) {
        AlertBuilder alert = AlertBuilder.forCrop(crop).type(type).message(message).detectedValue(detectedValue);
        return saveIfNoneOpen(crop, type, severity == null ? alert.automaticSeverity() : alert.severity(severity),
                crop.getEstate().getUser().getEmail());
    }

    private Optional<Alert> raiseAlertIfOutOfCropRange(Sensor sensor, SensorReading reading, String ownerEmail) {
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
        if (type == null) {
            return Optional.empty();
        }
        return saveIfNoneOpen(crop, type, AlertBuilder.forCrop(crop)
                .sensor(sensor)
                .type(type)
                .detectedValue(value)
                .message(describe(type, reading))
                .automaticSeverity(), ownerEmail);
    }

    private Optional<Alert> saveIfNoneOpen(Crop crop, AlertType type, AlertBuilder alert, String ownerEmail) {
        cropRepository.findByIdForUpdate(crop.getIdCrop());
        if (alertRepository.existsByCropIdCropAndAlertTypeAndAcknowledgedFalse(crop.getIdCrop(), type)) {
            return Optional.empty();
        }
        Alert saved = alertRepository.save(alert.build());
        events.publishEvent(new AlertRaisedEvent(ownerEmail, AlertResponse.from(saved)));
        return Optional.of(saved);
    }

    @Transactional(readOnly = true)
    public DashboardResponse summarizeCrop(Integer cropId) {
        return DashboardResponse.builder()
                .totalCrops(1)
                .totalSensors(sensorRepository.findByCropIdCropAndActiveTrue(cropId).size())
                .pendingAlerts((int) alertRepository.countByCropIdCropAndAcknowledgedFalse(cropId))
                .lastHumidity(latestValue(cropId, SensorType.SOIL_MOISTURE))
                .lastTemperature(latestValue(cropId, SensorType.AIR_TEMPERATURE))
                .lastPh(latestValue(cropId, SensorType.PH))
                .aiRecommendation(predictionRepository
                        .findByCropIdCropOrderByCreatedAtDesc(cropId, PageRequest.of(0, 1)).stream()
                        .map(AiPrediction::getRecommendation)
                        .findFirst()
                        .orElse(null))
                .latestReadings(readingRepository.findLatestByCrop(cropId, PageRequest.of(0, SUMMARY_READINGS))
                        .stream()
                        .map(SensorReadingResponse::from)
                        .toList())
                .activeAlerts(alertRepository
                        .findByCropIdCropAndAcknowledgedFalseOrderByCreatedAtDesc(cropId,
                                PageRequest.of(0, SUMMARY_ALERTS))
                        .stream()
                        .map(AlertResponse::from)
                        .toList())
                .build();
    }

    private BigDecimal latestValue(Integer cropId, SensorType type) {
        return readingRepository.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(cropId, type)
                .map(SensorReading::getValue)
                .orElse(null);
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

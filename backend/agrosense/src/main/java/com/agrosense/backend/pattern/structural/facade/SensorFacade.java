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
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.AlertCreator;
import com.agrosense.backend.pattern.creational.factory.alert.SensorFaultAlertCreator;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SensorFacade {

    private static final int SUMMARY_READINGS = 10;
    private static final int SUMMARY_ALERTS = 5;
    private static final BigDecimal FULL_SCALE = new BigDecimal("100");
    private static final AlertCreator FAULTS = new SensorFaultAlertCreator();

    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AiPredictionRepository predictionRepository;
    private final CropRepository cropRepository;
    private final SensorReadingPrototype readingPrototype;
    private final SensorReadingProcessor readingProcessor;
    private final ApplicationEventPublisher events;

    @Value("${alerts.stuck-readings:10}")
    private int stuckReadings = 10;

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
                ? AlertCreator.firstFor(AlertContext.ofReading(sensor, reading.getValue(), reading.getUnit()))
                        .flatMap(raised -> saveIfNoneOpen(raised, ownerEmail))
                : Optional.empty();
        raiseIfFaulty(sensor, reading, ownerEmail);
        return new Recorded(reading, alert);
    }

    @Transactional
    public Optional<Alert> raiseAlert(Crop crop, AlertType type, String message, Severity severity,
            BigDecimal detectedValue) {
        AlertBuilder alert = AlertBuilder.forCrop(crop).type(type).message(message).detectedValue(detectedValue);
        return saveIfNoneOpen((severity == null ? alert.automaticSeverity() : alert.severity(severity)).build(),
                crop.getEstate().getUser().getEmail());
    }

    @Transactional
    public Optional<Alert> raise(AlertCreator creator, AlertContext context) {
        return creator.evaluate(context)
                .flatMap(alert -> saveIfNoneOpen(alert, context.crop().getEstate().getUser().getEmail()));
    }

    /** For conditions that outlast their alert: an alert already answered since {@code since} is not raised again. */
    @Transactional
    public Optional<Alert> raiseOnceSince(AlertCreator creator, AlertContext context, LocalDateTime since) {
        return creator.evaluate(context)
                .filter(alert -> !alertRepository.existsByCropIdCropAndAlertTypeAndCreatedAtAfter(
                        context.crop().getIdCrop(), alert.getAlertType(), since))
                .flatMap(alert -> saveIfNoneOpen(alert, context.crop().getEstate().getUser().getEmail()));
    }

    private void raiseIfFaulty(Sensor sensor, SensorReading reading, String ownerEmail) {
        String fault = !ValidationDecorator.QUALITY_OK.equals(reading.getQuality()) ? "dio un valor físicamente imposible"
                : isStuckAtTheEndOfItsScale(sensor, reading)
                        ? "lleva " + stuckReadings + " lecturas seguidas sin moverse del extremo de su escala" : null;
        if (fault != null) {
            FAULTS.evaluate(AlertContext.ofFault(sensor, reading.getValue(), reading.getUnit(), fault))
                    .ifPresent(alert -> saveIfNoneOpen(alert, ownerEmail));
        }
    }

    private boolean isStuckAtTheEndOfItsScale(Sensor sensor, SensorReading reading) {
        boolean percentage = sensor.getSensorType() == SensorType.SOIL_MOISTURE
                || sensor.getSensorType() == SensorType.RELATIVE_HUMIDITY;
        BigDecimal value = reading.getValue();
        if (stuckReadings <= 0 || !percentage
                || (value.signum() != 0 && value.compareTo(FULL_SCALE) != 0)) {
            return false;
        }
        List<SensorReading> latest = readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(
                sensor.getIdSensor(), PageRequest.of(0, stuckReadings));
        return latest.size() == stuckReadings
                && latest.stream().allMatch(earlier -> earlier.getValue().compareTo(value) == 0);
    }

    private Optional<Alert> saveIfNoneOpen(Alert alert, String ownerEmail) {
        Integer cropId = alert.getCrop().getIdCrop();
        cropRepository.findByIdForUpdate(cropId);
        if (alertRepository.existsByCropIdCropAndAlertTypeAndAcknowledgedFalse(cropId, alert.getAlertType())) {
            return Optional.empty();
        }
        Alert saved = alertRepository.save(alert);
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
        return readingRepository
                .findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(cropId, type,
                        ValidationDecorator.QUALITY_OK)
                .map(SensorReading::getValue)
                .orElse(null);
    }
}

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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SensorFacade {

    private static final int SUMMARY_READINGS = 10;
    private static final int SUMMARY_ALERTS = 5;
    private static final BigDecimal FULL_SCALE = new BigDecimal("100");
    private static final AlertCreator FAULTS = new SensorFaultAlertCreator();

    /**
     * The alerts a valid reading of each sensor type closes when it is back in range. Frost is not here: it warns about
     * the night ahead from the forecast, so a mild reading now does not answer it.
     */
    static final Map<SensorType, Set<AlertType>> CLOSED_BY_A_READING = Map.of(
            SensorType.SOIL_MOISTURE, EnumSet.of(AlertType.LOW_HUMIDITY, AlertType.HIGH_HUMIDITY),
            SensorType.AIR_TEMPERATURE, EnumSet.of(AlertType.LOW_TEMPERATURE, AlertType.HIGH_TEMPERATURE),
            SensorType.RELATIVE_HUMIDITY, EnumSet.of(AlertType.LOW_AIR_HUMIDITY, AlertType.HIGH_AIR_HUMIDITY),
            SensorType.PH, EnumSet.of(AlertType.PH_OUT_OF_RANGE));

    /** Room left in alerts.message (VARCHAR 255) for the note that says why an alert closed itself. */
    private static final int MESSAGE_LENGTH = 255;

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

        boolean valid = ValidationDecorator.QUALITY_OK.equals(reading.getQuality());
        Optional<Alert> detected = valid
                ? AlertCreator.firstFor(AlertContext.ofReading(sensor, reading.getValue(), reading.getUnit()))
                : Optional.empty();
        Optional<Alert> alert = detected.flatMap(raised -> saveIfNoneOpen(raised, ownerEmail));
        if (valid) {
            closeWhatIsBackInRange(sensor, reading, detected.map(Alert::getAlertType).orElse(null));
        }
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

    /**
     * A valid reading answers the open alerts of its kind it no longer meets: a soil back in range closes «humedad
     * baja», and any reading of the crop closes «dispositivo sin reportar». The alert keeps what it said and gains why
     * it closed; one the reading still meets ({@code stillMet}) stays open.
     */
    private void closeWhatIsBackInRange(Sensor sensor, SensorReading reading, AlertType stillMet) {
        Set<AlertType> answered = EnumSet.of(AlertType.SENSOR_OFFLINE);
        answered.addAll(CLOSED_BY_A_READING.getOrDefault(sensor.getSensorType(), Set.of()));
        if (stillMet != null) {
            answered.remove(stillMet);
        }
        List<Alert> open = alertRepository.findByCropIdCropAndAlertTypeInAndAcknowledgedFalse(
                sensor.getCrop().getIdCrop(), answered);
        for (Alert alert : open) {
            alert.setAcknowledged(true);
            alert.setMessage(withClosingNote(alert, reading));
        }
        if (!open.isEmpty()) {
            alertRepository.saveAll(open);
        }
    }

    static String withClosingNote(Alert alert, SensorReading reading) {
        String value = reading.getValue().stripTrailingZeros().toPlainString()
                + (reading.getUnit() == null || reading.getUnit().isBlank() ? "" : " " + reading.getUnit().trim());
        String note = alert.getAlertType() == AlertType.SENSOR_OFFLINE
                ? " Se cerró sola: el dispositivo volvió a enviar datos."
                : " Se cerró sola: la lectura volvió al rango (" + value + ").";
        String message = alert.getMessage() == null ? "" : alert.getMessage().trim();
        return message.length() + note.length() <= MESSAGE_LENGTH ? message + note : message;
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

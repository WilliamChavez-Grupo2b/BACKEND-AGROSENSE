package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.IrrigationCycle;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FarmContextService {

    public static final int MAX_CROPS = 8;
    public static final int MAX_ALERTS = 5;
    public static final int MAX_ATTENDED_ALERTS = 3;
    public static final int MAX_IRRIGATIONS = 5;

    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final IrrigationRepository irrigationRepository;

    @Transactional(readOnly = true)
    public FarmSnapshot snapshot(String email) {
        List<Crop> crops = cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email);
        List<CropStatus> statuses = crops.stream().limit(MAX_CROPS).map(this::status).toList();
        List<OpenAlert> alerts = alertService.findOpen(email, MAX_ALERTS).stream()
                .map(alert -> new OpenAlert(alert.getCropName(), alert.getMessage(), alert.getAlertType(),
                        alert.getSeverity(), alert.getDetectedValue(), alert.getCreatedAt()))
                .toList();
        List<OpenAlert> attended = alertRepository
                .findByCropEstateUserEmailAndAcknowledgedTrueOrderByCreatedAtDesc(email,
                        PageRequest.of(0, MAX_ATTENDED_ALERTS))
                .stream()
                .map(alert -> new OpenAlert(alert.getCrop().getName(), alert.getMessage(),
                        alert.getAlertType() == null ? null : alert.getAlertType().name(),
                        alert.getSeverity() == null ? null : alert.getSeverity().name(),
                        alert.getDetectedValue(), alert.getCreatedAt()))
                .toList();
        List<IrrigationCycle> irrigations = irrigationRepository
                .findByCropEstateUserEmailOrderByStartedAtDesc(email, PageRequest.of(0, MAX_IRRIGATIONS))
                .stream()
                .map(cycle -> new IrrigationCycle(cycle.getCrop().getName(), cycle.getStartedAt(),
                        cycle.getDurationMin(), cycle.getWaterLiters(),
                        cycle.getType() == null ? null : cycle.getType().name(), cycle.getReason()))
                .toList();
        return new FarmSnapshot(crops.size(),
                (int) sensorRepository.countByCropEstateUserEmailAndActiveTrue(email),
                (int) alertRepository.countByCropEstateUserEmailAndAcknowledgedFalse(email),
                statuses, alerts, attended, irrigations);
    }

    private CropStatus status(Crop crop) {
        Estate estate = crop.getEstate();
        return new CropStatus(crop.getName(),
                latest(crop, SensorType.SOIL_MOISTURE), crop.getHumidityMin(), crop.getHumidityMax(),
                latest(crop, SensorType.AIR_TEMPERATURE), crop.getTempMin(), crop.getTempMax(),
                latest(crop, SensorType.PH), crop.getPhMin(), crop.getPhMax(),
                crop.getVariety(), crop.getStage() == null ? null : crop.getStage().name(), crop.getSowingDate(),
                estate == null ? null : estate.getName(), estate == null ? null : estate.getLocation());
    }

    private BigDecimal latest(Crop crop, SensorType type) {
        return readingRepository
                .findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(crop.getIdCrop(), type,
                        ValidationDecorator.QUALITY_OK)
                .map(SensorReading::getValue)
                .orElse(null);
    }
}

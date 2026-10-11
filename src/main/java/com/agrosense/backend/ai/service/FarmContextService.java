package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Reads what the assistant needs to know about one user's farm. Only that user's own data is read. */
@Service
@RequiredArgsConstructor
public class FarmContextService {

    /** Crops described one by one; a farm with more gets the first ones by name and a count of the rest. */
    public static final int MAX_CROPS = 8;
    public static final int MAX_ALERTS = 5;

    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;

    @Transactional(readOnly = true)
    public FarmSnapshot snapshot(String email) {
        List<Crop> crops = cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email);
        List<CropStatus> statuses = crops.stream().limit(MAX_CROPS)
                .map(crop -> new CropStatus(crop.getName(),
                        latest(crop, SensorType.SOIL_MOISTURE), crop.getHumidityMin(), crop.getHumidityMax(),
                        latest(crop, SensorType.AIR_TEMPERATURE), crop.getTempMin(), crop.getTempMax(),
                        latest(crop, SensorType.PH), crop.getPhMin(), crop.getPhMax()))
                .toList();
        List<OpenAlert> alerts = alertService.findOpen(email, MAX_ALERTS).stream()
                .map(alert -> new OpenAlert(alert.getCropName(), alert.getMessage()))
                .toList();
        return new FarmSnapshot(crops.size(),
                (int) sensorRepository.countByCropEstateUserEmailAndActiveTrue(email),
                (int) alertRepository.countByCropEstateUserEmailAndAcknowledgedFalse(email),
                statuses, alerts);
    }

    private BigDecimal latest(Crop crop, SensorType type) {
        return readingRepository
                .findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(crop.getIdCrop(), type)
                .map(SensorReading::getValue)
                .orElse(null);
    }
}

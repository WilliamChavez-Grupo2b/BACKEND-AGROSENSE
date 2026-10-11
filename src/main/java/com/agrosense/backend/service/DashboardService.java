package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int LATEST_READINGS = 10;
    private static final int ACTIVE_ALERTS = 5;

    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AiPredictionRepository predictionRepository;
    private final AlertService alertService;
    private final SensorFacade sensorFacade;

    /** The summary of one of the user's crops. Another user's crop is reported as missing. */
    @Transactional(readOnly = true)
    public DashboardResponse buildForCrop(String email, Integer cropId) {
        cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
        return sensorFacade.summarizeCrop(cropId);
    }

    @Transactional(readOnly = true)
    public DashboardResponse build(String email) {
        return DashboardResponse.builder()
                .totalCrops((int) cropRepository.countByEstateUserEmailAndActiveTrue(email))
                .totalSensors((int) sensorRepository.countByCropEstateUserEmailAndActiveTrue(email))
                .pendingAlerts((int) alertRepository.countByCropEstateUserEmailAndAcknowledgedFalse(email))
                .lastHumidity(latestValue(email, SensorType.SOIL_MOISTURE))
                .lastTemperature(latestValue(email, SensorType.AIR_TEMPERATURE))
                .lastPh(latestValue(email, SensorType.PH))
                .aiRecommendation(predictionRepository.findFirstByCropEstateUserEmailOrderByCreatedAtDesc(email)
                        .map(AiPrediction::getRecommendation)
                        .orElse(null))
                .latestReadings(readingRepository
                        .findBySensorCropEstateUserEmailOrderByRecordedAtDesc(email, PageRequest.of(0, LATEST_READINGS))
                        .stream()
                        .map(SensorReadingResponse::from)
                        .toList())
                .activeAlerts(alertService.findOpen(email, ACTIVE_ALERTS))
                .build();
    }

    private BigDecimal latestValue(String email, SensorType type) {
        return readingRepository
                .findFirstBySensorCropEstateUserEmailAndSensorSensorTypeOrderByRecordedAtDesc(email, type)
                .map(SensorReading::getValue)
                .orElse(null);
    }
}

package com.agrosense.backend.service;

import com.agrosense.backend.config.AppConfig;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.PredictionResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.pattern.structural.proxy.AiServiceProxy;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    static final String IRRIGATION = "IRRIGATION";

    private static final int MAX_PREDICTIONS = 100;

    private final AiServiceProxy aiServiceProxy;
    private final AiPredictionRepository predictionRepository;
    private final CropRepository cropRepository;
    private final SensorReadingRepository readingRepository;

    @Transactional(readOnly = true)
    public List<PredictionResponse> findHistory(String email, Integer cropId, int limit) {
        requireOwnedCrop(email, cropId);
        int size = Math.min(Math.max(limit, 1), MAX_PREDICTIONS);
        return predictionRepository.findByCropIdCropOrderByCreatedAtDesc(cropId, PageRequest.of(0, size)).stream()
                .map(PredictionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> currentConditions(String email, Integer cropId) {
        Crop crop = cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
        Map<String, Object> conditions = new HashMap<>();
        putLatest(conditions, "soil_moisture", cropId, SensorType.SOIL_MOISTURE);
        putLatest(conditions, "air_temperature", cropId, SensorType.AIR_TEMPERATURE);
        putLatest(conditions, "ph", cropId, SensorType.PH);
        putRange(conditions, "humidity_min", "humidity_max", crop.getHumidityMin(), crop.getHumidityMax());
        putRange(conditions, "ph_min", "ph_max", crop.getPhMin(), crop.getPhMax());
        return conditions;
    }

    private static void putRange(Map<String, Object> conditions, String minimumKey, String maximumKey,
            BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) < 0) {
            conditions.put(minimumKey, minimum);
            conditions.put(maximumKey, maximum);
        }
    }

    @Async(AppConfig.ASYNC_EXECUTOR)
    public void requestIrrigationPrediction(Integer cropId, Map<String, Object> sensorData) {
        if (!cropRepository.existsById(cropId)) {
            log.warn("Irrigation prediction skipped: crop {} does not exist", cropId);
            return;
        }
        aiServiceProxy.predictIrrigation(cropId, sensorData).ifPresent(prediction -> {
            predictionRepository.save(AiPrediction.builder()
                    .crop(cropRepository.getReferenceById(cropId))
                    .type(IRRIGATION)
                    .confidence(prediction.confidence())
                    .recommendation(prediction.recommendation())
                    .modelUsed(prediction.modelUsed())
                    .build());
            log.info("Irrigation prediction saved for crop {}", cropId);
        });
    }

    private void requireOwnedCrop(String email, Integer cropId) {
        cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
    }

    private void putLatest(Map<String, Object> conditions, String key, Integer cropId, SensorType type) {
        readingRepository.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(cropId, type)
                .ifPresent(reading -> conditions.put(key, reading.getValue()));
    }
}

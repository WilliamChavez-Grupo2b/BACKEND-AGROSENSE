package com.agrosense.backend.service;

import com.agrosense.backend.config.AppConfig;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.PredictionResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrediccionService {

    static final String IRRIGATION = "IRRIGATION";

    private static final int MAX_PREDICTIONS = 100;

    private final AiServiceProxy aiServiceProxy;
    private final AiPredictionRepository predictionRepository;
    private final CropRepository cropRepository;
    private final SensorReadingRepository readingRepository;

    /** The stored predictions of one of the user's crops, newest first. */
    @Transactional(readOnly = true)
    public List<PredictionResponse> findHistory(String email, Integer cropId, int limit) {
        requireOwnedCrop(email, cropId);
        int size = Math.min(Math.max(limit, 1), MAX_PREDICTIONS);
        return predictionRepository.findByCropIdCropOrderByCreatedAtDesc(cropId, PageRequest.of(0, size)).stream()
                .map(PredictionResponse::from)
                .toList();
    }

    /**
     * The latest soil moisture, air temperature and pH of one of the user's crops, in the shape the AI
     * service expects. A measurement the crop has no reading for is left out.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> currentConditions(String email, Integer cropId) {
        requireOwnedCrop(email, cropId);
        Map<String, Object> conditions = new HashMap<>();
        putLatest(conditions, "soil_moisture", cropId, SensorType.SOIL_MOISTURE);
        putLatest(conditions, "air_temperature", cropId, SensorType.AIR_TEMPERATURE);
        putLatest(conditions, "ph", cropId, SensorType.PH);
        return conditions;
    }

    /**
     * Asks the AI service for an irrigation prediction and stores it. Runs on the async pool, so the
     * caller never waits for the AI service; when it gives no answer, nothing is stored.
     */
    @Async(AppConfig.ASYNC_EXECUTOR)
    public void requestIrrigationPrediction(Integer cropId, Map<String, Object> sensorData) {
        if (!cropRepository.existsById(cropId)) {
            log.warn("Predicción de riego omitida: el cultivo {} no existe", cropId);
            return;
        }
        // The HTTP call happens outside any transaction; only the save below opens one.
        aiServiceProxy.predictIrrigation(cropId, sensorData).ifPresent(prediction -> {
            predictionRepository.save(AiPrediction.builder()
                    .crop(cropRepository.getReferenceById(cropId))
                    .type(IRRIGATION)
                    .confidence(prediction.confidence())
                    .recommendation(prediction.recommendation())
                    .modelUsed(prediction.modelUsed())
                    .build());
            log.info("Predicción de riego guardada para el cultivo {}", cropId);
        });
    }

    /** Another user's crop is reported as missing, so its existence is not revealed. */
    private void requireOwnedCrop(String email, Integer cropId) {
        cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
    }

    private void putLatest(Map<String, Object> conditions, String key, Integer cropId, SensorType type) {
        readingRepository.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(cropId, type)
                .ifPresent(reading -> conditions.put(key, reading.getValue()));
    }
}

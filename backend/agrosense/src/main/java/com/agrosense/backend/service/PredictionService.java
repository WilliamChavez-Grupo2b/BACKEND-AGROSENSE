package com.agrosense.backend.service;

import com.agrosense.backend.config.AppConfig;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.PredictionResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    static final String IRRIGATION = "IRRIGATION";

    private static final int MAX_PREDICTIONS = 100;
    private static final int HISTORY_HOURS = 6;
    private static final int HISTORY_BUCKET_MINUTES = 30;
    private static final int MAX_HISTORY_READINGS = 1000;

    private final AiServiceProxy aiServiceProxy;
    private final AiPredictionRepository predictionRepository;
    private final CropRepository cropRepository;
    private final SensorReadingRepository readingRepository;
    private final IrrigationService irrigationService;
    private final WeatherProvider weather;

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
        List<Map<String, Object>> history = moistureHistory(cropId);
        if (!history.isEmpty()) {
            conditions.put("moisture_history", history);
        }
        BigDecimal gain = irrigationService.moistureGainPerMinute(cropId);
        if (gain != null) {
            conditions.put("moisture_gain_per_minute", gain);
        }
        return conditions;
    }

    /** The soil moisture of the last hours, averaged per bucket so that a device's every 30 s stays a short list. */
    private List<Map<String, Object>> moistureHistory(Integer cropId) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<SensorReading>> buckets = new TreeMap<>();
        readingRepository.findBySensorCropIdCropAndSensorSensorTypeAndQualityAndRecordedAtAfterOrderByRecordedAtAsc(
                cropId, SensorType.SOIL_MOISTURE, ValidationDecorator.QUALITY_OK, now.minusHours(HISTORY_HOURS),
                PageRequest.of(0, MAX_HISTORY_READINGS))
                .forEach(reading -> buckets.computeIfAbsent(
                        Duration.between(reading.getRecordedAt(), now).toMinutes() / HISTORY_BUCKET_MINUTES,
                        bucket -> new ArrayList<>()).add(reading));
        List<Map<String, Object>> history = new ArrayList<>();
        buckets.forEach((bucket, readings) -> history.add(0, Map.of(
                "hours_ago", BigDecimal.valueOf(bucket * HISTORY_BUCKET_MINUTES / 60.0).setScale(2, RoundingMode.HALF_UP),
                "value", readings.stream().map(SensorReading::getValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(readings.size()), 2, RoundingMode.HALF_UP))));
        return history;
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
        aiServiceProxy.predictIrrigation(cropId, withRainOutlook(cropId, sensorData)).ifPresent(prediction -> {
            predictionRepository.save(AiPrediction.builder()
                    .crop(cropRepository.getReferenceById(cropId))
                    .type(IRRIGATION)
                    .confidence(prediction.confidence())
                    .recommendation(prediction.recommendation())
                    .modelUsed(prediction.modelUsed())
                    .build());
            log.info("Irrigation prediction saved for crop {}", cropId);
            irrigationService.orderFromPrediction(cropId, prediction);
        });
    }

    private Map<String, Object> withRainOutlook(Integer cropId, Map<String, Object> sensorData) {
        Map<String, Object> conditions = new HashMap<>(sensorData);
        cropRepository.findWithEstateByIdCrop(cropId)
                .map(Crop::getEstate)
                .filter(estate -> estate.getLatitude() != null)
                .flatMap(estate -> weather.outlook(estate.getLatitude(), estate.getLongitude()))
                .ifPresent(outlook -> {
                    if (outlook.rainProbability() != null) {
                        conditions.put("rain_probability", outlook.rainProbability());
                    }
                    if (outlook.rainMm() != null) {
                        conditions.put("rain_mm", outlook.rainMm());
                    }
                });
        return conditions;
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

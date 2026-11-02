package com.agrosense.backend.service;

import com.agrosense.backend.pattern.creational.builder.PredictionRequest;
import com.agrosense.backend.pattern.structural.proxy.AiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AiClientService implements AiService {

    private static final String SOIL_MOISTURE = "soil_moisture";

    private final WebClient aiWebClient;
    private final String irrigationPath;

    public AiClientService(WebClient aiWebClient,
            @Value("${ai.service.irrigation-path:/predict/irrigation}") String irrigationPath) {
        this.aiWebClient = aiWebClient;
        this.irrigationPath = irrigationPath;
    }

    @Override
    public Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData) {
        if (sensorData.get(SOIL_MOISTURE) == null) {
            log.info("Irrigation prediction skipped: crop {} has no soil moisture readings", cropId);
            return Optional.empty();
        }
        Map<String, Object> body;
        try {
            body = PredictionRequest.fromConditions(cropId, sensorData).toBody();
        } catch (IllegalStateException | NumberFormatException invalid) {
            log.warn("Irrigation prediction skipped for crop {}: {}", cropId, invalid.getMessage());
            return Optional.empty();
        }
        Map<?, ?> result = aiWebClient.post()
                .uri(irrigationPath)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        // A failed answer, not an absent one: the proxy must count it and let the local rules answer.
        if (result == null || result.get("recommendation") == null || result.get("recommendation").toString().isBlank()) {
            throw new IllegalStateException("The AI service answered without a recommendation");
        }
        Object model = result.get("model_used") != null ? result.get("model_used") : result.get("model");
        return Optional.of(new IrrigationPrediction(
                result.get("recommendation").toString(),
                confidence(result.get("confidence")),
                model == null ? null : model.toString(),
                Boolean.TRUE.equals(result.get("irrigate")),
                result.get("duration_minutes") instanceof Number minutes ? minutes.intValue() : null));
    }

    private static BigDecimal confidence(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal confidence = new BigDecimal(value.toString()).setScale(4, RoundingMode.HALF_UP);
            return confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0 ? null : confidence;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

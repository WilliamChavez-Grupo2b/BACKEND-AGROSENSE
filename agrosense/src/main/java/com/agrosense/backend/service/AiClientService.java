package com.agrosense.backend.service;

import com.agrosense.backend.pattern.structural.proxy.AiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * The real HTTP client of the AI service. It blocks and lets errors propagate; callers go through
 * AiServiceProxy, which decides whether to call at all and what to do when the call fails.
 */
@Service
public class AiClientService implements AiService {

    private final WebClient aiWebClient;
    private final String irrigationPath;

    public AiClientService(WebClient aiWebClient,
            @Value("${ai.service.irrigation-path:/predict/irrigation}") String irrigationPath) {
        this.aiWebClient = aiWebClient;
        this.irrigationPath = irrigationPath;
    }

    @Override
    public Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData) {
        Map<?, ?> result = aiWebClient.post()
                .uri(irrigationPath)
                .bodyValue(Map.of(
                        "id_crop", cropId,
                        "soil_moisture", sensorData.getOrDefault("soil_moisture", 60),
                        "air_temperature", sensorData.getOrDefault("air_temperature", 25),
                        "ph", sensorData.getOrDefault("ph", 6.5)))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        if (result == null || result.get("recommendation") == null) {
            return Optional.empty();
        }
        Object model = result.get("model_used") != null ? result.get("model_used") : result.get("model");
        return Optional.of(new IrrigationPrediction(
                result.get("recommendation").toString(),
                confidence(result.get("confidence")),
                model == null ? null : model.toString()));
    }

    /** A confidence between 0 and 1 with the four decimals the database keeps; anything else is dropped. */
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

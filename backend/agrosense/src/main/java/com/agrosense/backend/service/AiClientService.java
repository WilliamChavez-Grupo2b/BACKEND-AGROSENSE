package com.agrosense.backend.service;

import com.agrosense.backend.pattern.structural.proxy.AiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id_crop", cropId);
        for (String measurement : List.of(SOIL_MOISTURE, "air_temperature", "ph",
                "humidity_min", "humidity_max", "ph_min", "ph_max")) {
            if (sensorData.get(measurement) != null) {
                body.put(measurement, sensorData.get(measurement));
            }
        }
        Map<?, ?> result = aiWebClient.post()
                .uri(irrigationPath)
                .bodyValue(body)
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

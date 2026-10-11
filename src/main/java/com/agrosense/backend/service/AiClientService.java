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

/**
 * The real HTTP client of the AI service. It blocks and lets errors propagate; callers go through
 * AiServiceProxy, which decides whether to call at all and what to do when the call fails.
 */
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
        // The decision rests on the soil moisture: without a reading of it there is nothing to predict
        // from, and a made-up value would come back as advice about a measurement nobody took.
        if (sensorData.get(SOIL_MOISTURE) == null) {
            log.info("Predicción de riego omitida: el cultivo {} no tiene lecturas de humedad del suelo", cropId);
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id_crop", cropId);
        // Only what was measured is sent; the service works without the temperature and the pH, and
        // falls back to average limits for a crop that comes without its own.
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

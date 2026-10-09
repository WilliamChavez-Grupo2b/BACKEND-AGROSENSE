package com.agrosense.backend.service;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class AiClientService {
    // NOTE: requests are sent once; AiConfigManager#getMaxAttempts is available for a retry policy.

    private final RestClient restClient;
    private final String irrigationPath;
    private final AiConfigManager config = AiConfigManager.getInstance();

    public AiClientService(
            @Value("${ai.service.url:}") String aiServiceUrl,
            @Value("${ai.service.irrigation-path:/predict/irrigation}") String irrigationPath,
            @Value("${ai.service.timeout-ms:5000}") int timeoutMs,
            @Value("${ai.service.max-attempts:1}") int maxAttempts) {
        // The singleton holds the AI settings for the whole application; this is where they are loaded.
        config.configure(aiServiceUrl, timeoutMs, !aiServiceUrl.isBlank(), maxAttempts);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.irrigationPath = irrigationPath;
        this.restClient = RestClient.builder()
                .baseUrl(aiServiceUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Async
    public void requestIrrigationPrediction(Integer cropId, Map<String, Object> sensorData) {
        if (!config.isEnabled()) {
            log.debug("AI service URL is not configured, skipping prediction for crop {}", cropId);
            return;
        }
        try {
            Map<?, ?> result = restClient.post()
                    .uri(irrigationPath)
                    .body(Map.of(
                            "id_crop", cropId,
                            "soil_moisture", sensorData.getOrDefault("soil_moisture", 60),
                            "air_temperature", sensorData.getOrDefault("air_temperature", 25),
                            "ph", sensorData.getOrDefault("ph", 6.5)))
                    .retrieve()
                    .body(Map.class);

            if (result != null) {
                log.info("AI prediction received for crop {}: {}", cropId, result.get("recommendation"));
            }
        } catch (Exception e) {
            // The backend must keep working when the AI service is unavailable.
            log.warn("AI service not available for crop {}: {}", cropId, e.getMessage());
        }
    }
}

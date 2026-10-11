package com.agrosense.backend.pattern.structural.proxy;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.service.AiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Proxy pattern: stands in front of the real AI client. It skips the call when the service is not
 * configured, retries a failed call up to the configured number of attempts, and never lets a failure
 * of the AI service reach the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceProxy implements AiService {

    private final AiClientService aiClient;
    private final AiConfigManager config;

    @Override
    public Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData) {
        AiConfigManager.Settings settings = config.getSettings();
        if (!settings.enabled()) {
            log.debug("Servicio de IA no configurado; se omite la predicción del cultivo {}", cropId);
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                return aiClient.predictIrrigation(cropId, sensorData);
            } catch (RuntimeException exception) {
                log.warn("Servicio de IA no disponible para el cultivo {} (intento {} de {}): {}",
                        cropId, attempt, settings.maxAttempts(), exception.getMessage());
            }
        }
        return Optional.empty();
    }
}

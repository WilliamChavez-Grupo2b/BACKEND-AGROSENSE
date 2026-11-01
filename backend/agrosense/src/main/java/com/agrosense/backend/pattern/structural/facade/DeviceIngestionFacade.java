package com.agrosense.backend.pattern.structural.facade;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.security.DeviceRateLimiter;
import com.agrosense.backend.service.DeviceEventService;
import com.agrosense.backend.service.PredictionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one call that receives a device event. DeviceEventService authenticates it and stores it inside a transaction;
 * SensorFacade evaluates the alerts and SensorWebSocketHandler publishes both once that transaction commits. Then,
 * outside the transaction so a slow AI never holds it open, the crop gets a new irrigation prediction if it is due,
 * and the answer carries any irrigation order waiting for this device.
 */
@Slf4j
@Component
public class DeviceIngestionFacade {

    private final DeviceEventService events;
    private final PredictionService predictions;
    private final AiConfigManager aiConfig;
    private final PendingIrrigationCommands commands;
    private final DeviceRateLimiter rateLimiter;
    private final Duration predictionInterval;
    private final Clock clock;
    private final Map<Integer, Instant> lastPrediction = new ConcurrentHashMap<>();

    @Autowired
    public DeviceIngestionFacade(DeviceEventService events, PredictionService predictions, AiConfigManager aiConfig,
            PendingIrrigationCommands commands, DeviceRateLimiter rateLimiter,
            @Value("${ai.ingestion.prediction-minutes:30}") int predictionMinutes) {
        this(events, predictions, aiConfig, commands, rateLimiter, Duration.ofMinutes(predictionMinutes),
                Clock.systemUTC());
    }

    public DeviceIngestionFacade(DeviceEventService events, PredictionService predictions, AiConfigManager aiConfig,
            PendingIrrigationCommands commands, DeviceRateLimiter rateLimiter, Duration predictionInterval,
            Clock clock) {
        this.events = events;
        this.predictions = predictions;
        this.aiConfig = aiConfig;
        this.commands = commands;
        this.rateLimiter = rateLimiter;
        this.predictionInterval = predictionInterval;
        this.clock = clock;
    }

    public Map<String, Object> ingest(String deviceKey, DeviceEventRequest request) {
        rateLimiter.count(request.getDeviceId());
        Map<String, Object> answer = events.receive(request.getDeviceId(), deviceKey, request.getType(),
                request.getTs(), request.getData());
        try {
            requestPredictionIfDue(answer);
        } catch (RuntimeException failure) {
            log.warn("The event was stored but its prediction could not be requested: {}", failure.getMessage());
        }
        Integer cropId = (Integer) ((Map<?, ?>) answer.get("event")).get("cropId");
        try {
            confirm(cropId, request.getAck());
            commands.take(cropId).ifPresent(command -> answer.put("command", describe(command)));
        } catch (RuntimeException failure) {
            log.warn("The event was stored but its irrigation order could not be handled: {}", failure.getMessage());
        }
        return answer;
    }

    private void confirm(Integer cropId, JsonNode ack) {
        if (ack == null || !ack.path("command_id").isIntegralNumber()) {
            return;
        }
        Integer ranSeconds = ack.path("ran_seconds").isIntegralNumber() ? ack.path("ran_seconds").asInt() : null;
        if (!commands.confirm(cropId, ack.path("command_id").asInt(), ack.path("result").asString(""), ranSeconds)) {
            log.warn("Crop {} confirmed an order that was not waiting for it", cropId);
        }
    }

    private static Map<String, Object> describe(IrrigationCommand command) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("id", command.id());
        described.put("type", "irrigate");
        described.put("source", command.source());
        described.put("minutes", command.minutes());
        if (command.confidence() != null) {
            described.put("confidence", command.confidence());
        }
        return described;
    }

    private void requestPredictionIfDue(Map<String, Object> answer) {
        if (predictionInterval.isZero() || !aiConfig.isEnabled()) {
            return;
        }
        Map<?, ?> event = (Map<?, ?>) answer.get("event");
        boolean hasSoilMoisture = event.get("readings") instanceof List<?> readings && readings.stream()
                .anyMatch(reading -> reading instanceof SensorReadingResponse measured
                        && SensorType.SOIL_MOISTURE.name().equals(measured.getSensorType()));
        Integer cropId = (Integer) event.get("cropId");
        if (hasSoilMoisture && claimPrediction(cropId)) {
            predictions.requestIrrigationPrediction(cropId,
                    predictions.currentConditions((String) answer.get("ownerEmail"), cropId));
        }
    }

    private boolean claimPrediction(Integer cropId) {
        Instant now = clock.instant();
        boolean[] due = {false};
        lastPrediction.compute(cropId, (key, last) -> {
            due[0] = last == null || !now.isBefore(last.plus(predictionInterval));
            return due[0] ? now : last;
        });
        return due[0];
    }
}

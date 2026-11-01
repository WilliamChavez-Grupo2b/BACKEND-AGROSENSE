package com.agrosense.backend.pattern.structural.proxy;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.service.AiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in front of the AI service with the same interface. Protection: after {@link #FAILURES_TO_PAUSE} failed
 * calls in a row the service is left alone for {@link #PAUSE}. Cache: the same conditions get the same answer for
 * {@link #CACHE_FOR}. Fallback: a failed call that has a soil moisture is answered by {@link #localPrediction}.
 */
@Slf4j
@Component
public class AiServiceProxy implements AiService {

    public static final String LOCAL_MODEL = "agrosense-reglas-locales";

    public static final int FAILURES_TO_PAUSE = 3;
    public static final Duration PAUSE = Duration.ofMinutes(1);
    public static final Duration CACHE_FOR = Duration.ofMinutes(10);

    private static final int MAX_CACHED = 256;
    private static final BigDecimal LOCAL_CONFIDENCE = new BigDecimal("0.5000");
    private static final BigDecimal DEFAULT_HUMIDITY_MIN = new BigDecimal("40");

    private record Cached(IrrigationPrediction prediction, Instant expiresAt) {
    }

    private final AiClientService aiClient;
    private final AiConfigManager config;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicInteger failuresInARow = new AtomicInteger();
    private volatile Instant pausedUntil = Instant.MIN;

    @Autowired
    public AiServiceProxy(AiClientService aiClient, AiConfigManager config) {
        this(aiClient, config, Clock.systemUTC());
    }

    public AiServiceProxy(AiClientService aiClient, AiConfigManager config, Clock clock) {
        this.aiClient = aiClient;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData) {
        AiConfigManager.Settings settings = config.getSettings();
        if (!settings.enabled()) {
            log.debug("AI service not configured; skipping the prediction for crop {}", cropId);
            return Optional.empty();
        }
        Instant now = clock.instant();
        String key = cropId + ":" + new TreeMap<>(sensorData);
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return Optional.of(cached.prediction());
        }
        if (now.isBefore(pausedUntil)) {
            return localPrediction(sensorData);
        }
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                Optional<IrrigationPrediction> prediction = aiClient.predictIrrigation(cropId, sensorData);
                failuresInARow.set(0);
                prediction.ifPresent(answer -> remember(key, answer, now));
                return prediction;
            } catch (RuntimeException exception) {
                log.warn("AI service unavailable for crop {} (attempt {} of {}): {}",
                        cropId, attempt, settings.maxAttempts(), exception.getMessage());
            }
        }
        if (failuresInARow.incrementAndGet() >= FAILURES_TO_PAUSE) {
            failuresInARow.set(0);
            pausedUntil = now.plus(PAUSE);
            log.warn("AI service paused for {} after {} failed calls in a row", PAUSE, FAILURES_TO_PAUSE);
        }
        return localPrediction(sensorData);
    }

    private void remember(String key, IrrigationPrediction prediction, Instant now) {
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        if (cache.size() < MAX_CACHED) {
            cache.put(key, new Cached(prediction, now.plus(CACHE_FOR)));
        }
    }

    /** The crop's own minimum against the last soil moisture: all that can be said without a model. */
    static Optional<IrrigationPrediction> localPrediction(Map<String, Object> sensorData) {
        Object moisture = sensorData.get("soil_moisture");
        if (moisture == null) {
            return Optional.empty();
        }
        Object minimum = sensorData.get("humidity_min");
        BigDecimal soil = new BigDecimal(moisture.toString());
        BigDecimal lowest = minimum == null ? DEFAULT_HUMIDITY_MIN : new BigDecimal(minimum.toString());
        String recommendation = soil.compareTo(lowest) < 0
                ? "Riega: la humedad del suelo (" + soil.toPlainString() + " %) está por debajo del mínimo del cultivo ("
                        + lowest.toPlainString() + " %). Recomendación local: el servicio de IA no respondió."
                : "No hace falta regar por ahora: la humedad del suelo es " + soil.toPlainString()
                        + " %. Recomendación local: el servicio de IA no respondió.";
        return Optional.of(new IrrigationPrediction(recommendation, LOCAL_CONFIDENCE, LOCAL_MODEL));
    }
}

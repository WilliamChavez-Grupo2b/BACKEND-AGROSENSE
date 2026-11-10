package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Reads the ESP32 over TCP and hands each adapted line to DeviceIngestionFacade, the same road as the webhook:
 * calibration, storing, alerts, live publication and the irrigation prediction all happen there and only there.
 *
 * <p>Off unless ESP32_TCP_ENABLED=true. It starts once the application is ready, on a thread of its own, so a missing
 * ESP32 never delays or stops the backend. The connection carries no X-Device-Key: the device is trusted because it is
 * configured (ESP32_DEVICE_ID) and reached on its own local network. See docs/ARQUITECTURA.md, decision 40.
 */
@Slf4j
@Service
public class Esp32TcpIngestionService {

    private final Esp32ReadingAdapter adapter;
    private final DeviceIngestionFacade ingestion;
    private final boolean enabled;
    private final String deviceId;
    private final Esp32TcpClient client;

    public Esp32TcpIngestionService(Esp32ReadingAdapter adapter, DeviceIngestionFacade ingestion,
            @Value("${esp32.tcp.enabled:false}") boolean enabled,
            @Value("${esp32.tcp.host:192.168.4.1}") String host,
            @Value("${esp32.tcp.port:8080}") int port,
            @Value("${esp32.device-id:RPI-DEMO-01}") String deviceId,
            @Value("${esp32.tcp.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${esp32.tcp.read-timeout-ms:120000}") long readTimeoutMs,
            @Value("${esp32.tcp.first-retry-ms:2000}") long firstRetryMs,
            @Value("${esp32.tcp.longest-retry-ms:30000}") long longestRetryMs) {
        this.adapter = adapter;
        this.ingestion = ingestion;
        this.enabled = enabled;
        this.deviceId = deviceId;
        this.client = new Esp32TcpClient(host, port, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(readTimeoutMs), Duration.ofMillis(firstRetryMs), Duration.ofMillis(longestRetryMs),
                this::receive);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        log.info("ESP32: reading device {} over TCP", deviceId);
        client.start();
    }

    @PreDestroy
    public void stop() {
        client.stop();
    }

    public boolean connected() {
        return client.connected();
    }

    public int connections() {
        return client.connections();
    }

    void receive(String line) {
        adapter.adapt(line, deviceId).ifPresent(event -> {
            try {
                Map<String, Object> answer = ingestion.ingestFromConfiguredDevice(event);
                Map<?, ?> stored = (Map<?, ?>) answer.get("event");
                log.debug("ESP32: stored {} reading(s), {} alert(s)", ((java.util.List<?>) stored.get("readings")).size(),
                        ((java.util.List<?>) stored.get("alerts")).size());
            } catch (ApiException refused) {
                log.warn("ESP32: line refused ({}): {}", refused.getStatus().value(), refused.getMessage());
            }
        });
    }
}

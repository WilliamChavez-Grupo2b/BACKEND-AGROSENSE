package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator.Window;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import com.agrosense.backend.service.DeviceEventService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Reads the ESP32 over TCP and hands each averaged reading to DeviceIngestionFacade, the same road as the webhook:
 * calibration, storing, alerts, live publication and the irrigation prediction happen there and only there.
 *
 * <p>Adapter: Esp32TcpClient gives lines, Esp32BlockParser turns each block into a reading, Esp32ReadingAggregator
 * averages them over ESP32_AGGREGATE_SECONDS, and this class writes the result as the event POST /api/internal/events
 * receives. Off unless ESP32_TCP_ENABLED=true; it starts once the application is ready, on a thread of its own, so a
 * missing ESP32 never delays or stops the backend. The connection carries no X-Device-Key: the device is trusted
 * because it is configured (ESP32_DEVICE_ID) and reached on a private address. See docs/ARQUITECTURA.md, decision 40.
 */
@Slf4j
@Service
public class Esp32TcpIngestionService {

    /** The field's zone, for the time each averaged reading is stamped with. */
    public static final ZoneOffset FIELD_ZONE = ZoneOffset.ofHours(-5);

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final DeviceIngestionFacade ingestion;
    private final boolean enabled;
    private final String deviceId;
    private final int aggregateSeconds;
    private final Clock clock;
    private final Esp32BlockParser parser = new Esp32BlockParser();
    private final Esp32ReadingAggregator aggregator;
    private final Esp32TcpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong receivedBlocks = new AtomicLong();
    private final AtomicLong storedReadings = new AtomicLong();
    private final AtomicLong refusedReadings = new AtomicLong();
    private volatile Instant lastBlockAt;
    private volatile Instant lastStoredAt;

    public Esp32TcpIngestionService(DeviceIngestionFacade ingestion,
            @Value("${esp32.tcp.enabled:false}") boolean enabled,
            @Value("${esp32.tcp.host:192.168.4.1}") String host,
            @Value("${esp32.tcp.port:8080}") int port,
            @Value("${esp32.device-id:ESP32-PLANTICAS}") String deviceId,
            @Value("${esp32.aggregate-seconds:60}") int aggregateSeconds,
            @Value("${esp32.tcp.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${esp32.tcp.read-timeout-ms:30000}") long readTimeoutMs,
            @Value("${esp32.tcp.first-retry-ms:2000}") long firstRetryMs,
            @Value("${esp32.tcp.longest-retry-ms:30000}") long longestRetryMs,
            @Value("${esp32.tcp.allow-loopback:false}") boolean allowLoopback) {
        if (enabled && !isPrivate(host, allowLoopback)) {
            throw new IllegalStateException("ESP32_TCP_HOST must be a private IPv4 address (10.x, 172.16-31.x or "
                    + "192.168.x), not '" + host + "'");
        }
        if (aggregateSeconds < 1) {
            throw new IllegalStateException("ESP32_AGGREGATE_SECONDS must be at least 1");
        }
        this.ingestion = ingestion;
        this.enabled = enabled;
        this.deviceId = deviceId;
        this.aggregateSeconds = aggregateSeconds;
        this.clock = Clock.systemUTC();
        this.aggregator = new Esp32ReadingAggregator(Duration.ofSeconds(aggregateSeconds), clock);
        this.client = new Esp32TcpClient(host, port, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(readTimeoutMs), Duration.ofMillis(firstRetryMs), Duration.ofMillis(longestRetryMs),
                this::receive, this::disconnected);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        log.info("ESP32: reading device {} over TCP, one reading every {} s", deviceId, aggregateSeconds);
        client.start();
    }

    @PreDestroy
    public void stop() {
        client.stop();
    }

    public boolean enabled() {
        return enabled;
    }

    /** What GET /api/devices/esp32/status shows: no address, no key, nothing sensitive. */
    public Esp32ReceiverStatus status() {
        return new Esp32ReceiverStatus(enabled, client.connected(), deviceId, aggregateSeconds, inField(lastBlockAt),
                inField(lastStoredAt), receivedBlocks.get(), storedReadings.get(), refusedReadings.get(),
                parser.discardedBlocks(),
                client.skippedLines(), client.connections());
    }

    public boolean connected() {
        return client.connected();
    }

    public int connections() {
        return client.connections();
    }

    void receive(String line) {
        parser.accept(line).ifPresent(this::collect);
        aggregator.closeIfDue().ifPresent(this::store);
    }

    void disconnected() {
        parser.finish().ifPresent(this::collect);
        aggregator.flush().ifPresent(this::store);
    }

    private void collect(Esp32Reading reading) {
        lastBlockAt = clock.instant();
        receivedBlocks.incrementAndGet();
        aggregator.add(reading).ifPresent(this::store);
    }

    private void store(Window window) {
        try {
            ingestion.ingestFromConfiguredDevice(eventOf(window));
            storedReadings.incrementAndGet();
            lastStoredAt = window.endsAt();
            log.debug("ESP32: stored the average of {} block(s)", window.blocks());
        } catch (ApiException refused) {
            refusedReadings.incrementAndGet();
            log.warn("ESP32: averaged reading refused ({}): {}", refused.getStatus().value(), refused.getMessage());
        } catch (RuntimeException failure) {
            refusedReadings.incrementAndGet();
            log.warn("ESP32: averaged reading not stored: {}", failure.getMessage());
        }
    }

    DeviceEventRequest eventOf(Window window) {
        ObjectNode data = json.createObjectNode();
        Esp32Reading average = window.average();
        if (average.airTemperature() != null) {
            data.put("air_temperature", average.airTemperature());
        }
        if (average.relativeHumidity() != null) {
            data.put("relative_humidity", average.relativeHumidity());
        }
        if (average.soilMoisture() != null) {
            data.put("soil_moisture", average.soilMoisture());
        }
        DeviceEventRequest event = new DeviceEventRequest();
        event.setDeviceId(deviceId);
        event.setType(DeviceEventService.READING);
        event.setTs(json.createObjectNode().put("ts", OffsetDateTime.ofInstant(window.endsAt(), FIELD_ZONE).toString())
                .get("ts"));
        event.setData(data);
        return event;
    }

    private static OffsetDateTime inField(Instant moment) {
        return moment == null ? null : OffsetDateTime.ofInstant(moment, FIELD_ZONE);
    }

    /** Only a literal private IPv4 address: no name is looked up, so nothing outside the local network is reached. */
    public static boolean isPrivate(String host, boolean allowLoopback) {
        var address = IPV4.matcher(host == null ? "" : host.strip());
        if (!address.matches()) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(address.group(i + 1));
            if (octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (allowLoopback && octets[0] == 127);
    }
}

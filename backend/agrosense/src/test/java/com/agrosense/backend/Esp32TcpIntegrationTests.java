package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReceiverStatus;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fake ESP32: a ServerSocket on a random port that sends the real block every 100 ms. The backend connects by itself
 * once it is ready and stores one averaged reading per second (ESP32_AGGREGATE_SECONDS=1 here). The server then closes
 * and opens again on the same port, and the backend has to come back to it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agrosense-esp32;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "cors.allowed-origins=",
        "jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
        "agrosense.seed.password=seed-password",
        "login.max-attempts-per-address=0",
        "alerts.frost-watch=false",
        "esp32.tcp.enabled=true",
        "esp32.tcp.host=127.0.0.1",
        "esp32.tcp.allow-loopback=true",
        "esp32.device-id=RPI-DEMO-01",
        "esp32.aggregate-seconds=1",
        "esp32.tcp.first-retry-ms=100",
        "esp32.tcp.longest-retry-ms=400",
        // What .env.example and compose.yaml set for the ESP32, which now sends raw values.
        "sensor.calibration=AIR_TEMPERATURE=-8.5:1,RELATIVE_HUMIDITY=-6:1"
})
@ActiveProfiles("seed")
class Esp32TcpIntegrationTests {

    private static final Duration PATIENCE = Duration.ofSeconds(20);
    private static ServerSocket server;
    private static int port;

    @Autowired
    private Esp32TcpIngestionService esp32;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private SensorReadingRepository readingRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private SensorRepository sensorRepository;

    @DynamicPropertySource
    static void fakeEsp32(DynamicPropertyRegistry registry) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        port = server.getLocalPort();
        registry.add("esp32.tcp.port", () -> port);
    }

    @AfterAll
    static void closeTheFake() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private static String block(String temperature, String humidity, String soil) {
        return "===== PLANTICAS =====\r\n"
                + "Temperatura exterior: " + temperature + " C\r\n"
                + "Humedad relativa: " + humidity + " %\r\n"
                + "Humedad del suelo: " + soil + " %\r\n"
                + "\r\n";
    }

    @Test
    void averagedReadingsAreStoredRaiseAnAlertAndTheClientComesBackAfterTheServerCloses() throws Exception {
        Device device = deviceRepository.findByDeviceCode("RPI-DEMO-01").orElseThrow();
        Integer cropId = device.getCrop().getIdCrop();
        // The seed leaves a low moisture alert open on this crop, and an open alert is not repeated: answer it first.
        alertRepository.findAll().stream()
                .filter(alert -> alert.getCrop().getIdCrop().equals(cropId) && !alert.getAcknowledged())
                .forEach(alert -> {
                    alert.setAcknowledged(true);
                    alertRepository.save(alert);
                });
        long readingsBefore = readingRepository.count();
        long lowMoistureBefore = lowMoistureAlerts(cropId);

        int sent = 0;
        try (Socket esp = server.accept()) {
            OutputStream out = esp.getOutputStream();
            Instant until = Instant.now().plusMillis(3500);
            while (Instant.now().isBefore(until)) {
                // 12.50 % is below the coffee crop's soil range; nan leaves the air humidity out of one block.
                out.write(block("23.40", sent % 5 == 0 ? "nan" : "58.00", "12.50").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sent++;
                Thread.sleep(100);
            }
            waitFor(() -> esp32.status().storedReadings() >= 2, "two averaged readings");
        }
        waitFor(() -> !esp32.connected(), "the client notices the closed connection");

        Esp32ReceiverStatus status = esp32.status();
        long stored = readingRepository.count() - readingsBefore;
        // About one reading per field and second, and far fewer than one per block.
        assertThat(stored).isGreaterThanOrEqualTo(6).isLessThan(sent);
        assertThat(status.storedReadings()).isBetween(2L, 6L);
        assertThat(status.lastStoredAt()).isNotNull();
        assertThat(status.lastStoredAt().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
        assertThat(status.discardedBlocks()).isZero();
        assertThat(lowMoistureAlerts(cropId)).isGreaterThan(lowMoistureBefore);
        // The ESP32 sent 23.40 °C and 58.00 % raw: stored once corrected (-8.5, -6), not twice (6.40, 46.00).
        assertThat(latest("AS-002")).isEqualTo(14.90);
        assertThat(latest("AS-007")).isEqualTo(52.00);
        assertThat(latest("AS-001")).isEqualTo(12.50);  // the soil has no correction
        assertThat(deviceRepository.findByDeviceCode("RPI-DEMO-01").orElseThrow().getLastEventAt()).isNotNull();

        // The ESP32 restarts: the server goes away and comes back on the same port.
        server.close();
        int connectionsBefore = esp32.connections();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        server.setSoTimeout((int) PATIENCE.toMillis());
        long storedBefore = esp32.status().storedReadings();
        try (Socket again = server.accept()) {
            OutputStream out = again.getOutputStream();
            for (int i = 0; i < 15; i++) {
                out.write(block("24.00", "60.00", "45.00").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            }
            waitFor(() -> esp32.status().storedReadings() > storedBefore, "a reading after reconnecting");
        }
        assertThat(esp32.connections()).isGreaterThan(connectionsBefore);
    }

    private double latest(String sensorCode) {
        Integer sensorId = sensorRepository.findBySensorCode(sensorCode).orElseThrow().getIdSensor();
        return readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(sensorId, PageRequest.of(0, 1)).get(0)
                .getValue().doubleValue();
    }

    private long lowMoistureAlerts(Integer cropId) {
        return alertRepository.findAll().stream()
                .filter(alert -> alert.getCrop().getIdCrop().equals(cropId))
                .filter(alert -> alert.getAlertType() == AlertType.LOW_HUMIDITY)
                .count();
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        Instant limit = Instant.now().plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(limit)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}

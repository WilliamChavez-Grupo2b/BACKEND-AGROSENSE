package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
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
 * A fake ESP32: a ServerSocket on a random port that the backend connects to by itself once it is ready. It sends
 * three lines, closes the connection and the server, opens the server again on the same port, and the backend must
 * come back to it.
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
        "esp32.device-id=RPI-DEMO-01",
        "esp32.tcp.first-retry-ms=100",
        "esp32.tcp.longest-retry-ms=400"
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

    @Test
    void theLinesAreStoredRaiseAnAlertAndTheClientComesBackAfterTheServerCloses() throws Exception {
        Device device = deviceRepository.findByDeviceCode("RPI-DEMO-01").orElseThrow();
        Integer cropId = device.getCrop().getIdCrop();
        // The seed leaves a low moisture alert open on this crop, and an open alert is not repeated: answer it first.
        alertRepository.findAll().stream()
                .filter(alert -> alert.getCrop().getIdCrop().equals(cropId) && !alert.getAcknowledged())
                .forEach(alert -> {
                    alert.setAcknowledged(true);
                    alertRepository.save(alert);
                });
        long before = countReadings();
        long lowMoistureBefore = lowMoistureAlerts(cropId);

        try (Socket esp = server.accept()) {
            OutputStream out = esp.getOutputStream();
            out.write(("{\"temp\": 23.4, \"hum\": 58, \"suelo\": 44}\n"
                    + "basura sin datos\n"
                    + "temperatura=23.6,humedad_aire=57,humedad_suelo=12.5\n"
                    + "temp:23.8;hum:57;suelo:45\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            waitFor(() -> countReadings() >= before + 9, "the three good lines stored as nine readings");
        }
        assertThat(countReadings()).isEqualTo(before + 9);  // the line without data stored nothing
        assertThat(lowMoistureAlerts(cropId)).isGreaterThan(lowMoistureBefore);  // 12.5 % is below the crop's range
        assertThat(deviceRepository.findByDeviceCode("RPI-DEMO-01").orElseThrow().getLastEventAt()).isNotNull();

        // The ESP32 restarts: the server goes away and comes back on the same port.
        server.close();
        waitFor(() -> !esp32.connected(), "the client notices the closed connection");
        int connectionsBefore = esp32.connections();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        server.setSoTimeout((int) PATIENCE.toMillis());
        try (Socket again = server.accept()) {
            again.getOutputStream().write("suelo=50\n".getBytes(StandardCharsets.UTF_8));
            waitFor(() -> countReadings() >= before + 10, "a reading after reconnecting");
        }
        assertThat(esp32.connections()).isGreaterThan(connectionsBefore);
    }

    private long countReadings() {
        return readingRepository.count();
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

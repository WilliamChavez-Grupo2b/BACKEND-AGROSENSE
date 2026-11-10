package com.agrosense.backend;

import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadOnlyActuatorDriver;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReceiverStatus;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The ESP32 is switched on in the settings but nobody listens on its port: the backend must start and serve anyway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:agrosense-esp32-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
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
        "esp32.tcp.first-retry-ms=100",
        "esp32.tcp.longest-retry-ms=200"
})
@ActiveProfiles("seed")
class Esp32OffTests {

    @Autowired
    private Esp32TcpIngestionService esp32;
    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void aPortNobodyListensOn(DynamicPropertyRegistry registry) throws IOException {
        int free;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            free = probe.getLocalPort();
        }
        registry.add("esp32.tcp.port", () -> free);
    }

    @Test
    void theBackendStartsAndAnswersWhileTheEsp32IsAway() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);

        Esp32ReceiverStatus status = esp32.status();
        assertThat(status.enabled()).isTrue();
        assertThat(status.connected()).isFalse();
        assertThat(status.lastStoredAt()).isNull();
        assertThat(status.connections()).isZero();
        assertThat(status.receivedBlocks()).isZero();

        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/devices/esp32/status")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void aPublicOrNamedAddressIsRefusedWhenTheReceiverIsOn() {
        assertThatThrownBy(() -> new Esp32TcpIngestionService(null, true, "8.8.8.8", 8080, "RPI-DEMO-01", 60,
                5000, 30000, 2000, 30000, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private");
        assertThatThrownBy(() -> new Esp32TcpIngestionService(null, true, "esp32.example.com", 8080, "RPI-DEMO-01", 60,
                5000, 30000, 2000, 30000, false)).isInstanceOf(IllegalStateException.class);
        // Off, nothing is checked: the setting is not used.
        new Esp32TcpIngestionService(null, false, "8.8.8.8", 8080, "RPI-DEMO-01", 60, 5000, 30000, 2000, 30000, false);
    }

    @Test
    void anOrderForTheEsp32sCropIsOnlyRecordedAndOthersGoOn() {
        ActuatorDriver inner = mock(ActuatorDriver.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        Device esp = Device.builder().deviceCode("RPI-DEMO-01").crop(Crop.builder().idCrop(7).build()).build();
        when(devices.findByDeviceCode("RPI-DEMO-01")).thenReturn(Optional.of(esp));
        Esp32ReadOnlyActuatorDriver driver = new Esp32ReadOnlyActuatorDriver(inner, devices, "rpi-demo-01");

        driver.start(new IrrigationCommand(1, 7, 3, IrrigationCommand.MANUAL, null, 10));
        verify(inner, never()).start(any());

        IrrigationCommand other = new IrrigationCommand(2, 8, 3, IrrigationCommand.MANUAL, null, 11);
        driver.start(other);
        verify(inner).start(other);
    }
}

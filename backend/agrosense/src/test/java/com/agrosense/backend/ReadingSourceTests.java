package com.agrosense.backend;

import com.agrosense.backend.domain.enums.ReadingSource;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.event.SensorReadingRecordedEvent;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.DeviceEventService;
import com.agrosense.backend.service.DeviceService;
import com.agrosense.backend.service.VirtualSensorSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sensor_readings.source: every way in stamps its own origin, the virtual sensor never writes while real readings
 * arrive and goes through the same pipeline, and GET /api/data-sources answers the traceability catalogue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-sources;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password",
		"agrosense.seed.device-key=demo-device-key-for-tests",
		"login.max-attempts-per-address=0",
		"ai.ingestion.prediction-minutes=0"
})
@ActiveProfiles("seed")
@RecordApplicationEvents
class ReadingSourceTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private CropRepository cropRepository;
	@Autowired
	private SensorRepository sensorRepository;
	@Autowired
	private SensorReadingRepository readingRepository;
	@Autowired
	private SensorFacade sensorFacade;
	@Autowired
	private DeviceEventService deviceEvents;
	@Autowired
	private DeviceService deviceService;
	@Autowired
	private ApplicationEvents applicationEvents;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void theSampleRowsAreMarkedSeed() {
		Sensor seeded = sensorRepository.findBySensorCode("AS-001").orElseThrow();

		List<SensorReading> readings = readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(
				seeded.getIdSensor(), PageRequest.of(0, 50));

		assertThat(readings).isNotEmpty().allMatch(reading -> reading.getSource() == ReadingSource.SEED);
	}

	@Test
	void aReadingTypedThroughTheApiIsManual() throws Exception {
		HttpResponse<String> created = send("POST", "/api/readings", token(),
				"{\"sensorCode\":\"AS-003\",\"value\":6.4}");

		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode body = JSON.readTree(created.body());
		assertThat(body.path("source").asString()).isEqualTo("MANUAL");
		assertThat(readingRepository.findById(body.path("idReading").asLong()).orElseThrow().getSource())
				.isEqualTo(ReadingSource.MANUAL);
	}

	@Test
	void aDeviceEventIsDeviceAndTheConfiguredEsp32IsEsp32Tcp() {
		Crop crop = cropRepository.save(Crop.builder().estate(anyCrop().getEstate()).name("Origen").build());
		sensorRepository.save(Sensor.builder().crop(crop).sensorCode("SRC-01").sensorType(SensorType.PH).build());
		String key = deviceService.create(EMAIL, crop.getIdCrop(), "ESP32-ORIGEN").getDeviceKey();
		JsonNode data = JSON.readTree("{\"ph\":6.5}");

		Map<String, Object> fromDevice = deviceEvents.receive("ESP32-ORIGEN", key, "reading", null, data);
		Map<String, Object> fromTcp = deviceEvents.receiveFromConfiguredDevice("ESP32-ORIGEN", "reading", null, data);

		assertThat(firstReading(fromDevice).getSource()).isEqualTo("DEVICE");
		assertThat(firstReading(fromTcp).getSource()).isEqualTo("ESP32_TCP");
		assertThat(readingRepository.findById(firstReading(fromTcp).getIdReading()).orElseThrow().getSource())
				.isEqualTo(ReadingSource.ESP32_TCP);
	}

	@Test
	void theSimulatorNeverWritesWhileARealReadingIsRecent() {
		Sensor sensor = newSensor("SIM-REAL", SensorType.SOIL_MOISTURE);
		LocalDateTime now = LocalDateTime.now();
		sensorFacade.record(SensorReadingData.builder().sensorCode("SIM-REAL").value(new BigDecimal("41"))
				.recordedAt(now.minusMinutes(2)).source(ReadingSource.ESP32_TCP).build());
		long before = readingRepository.count();

		boolean simulated = simulator(now).simulate(sensor, now);

		assertThat(simulated).isFalse();
		assertThat(readingRepository.count()).isEqualTo(before);
	}

	@Test
	void theSimulatorFillsASilentSensorThroughTheSamePipeline() {
		Sensor sensor = newSensor("SIM-SILENT", SensorType.AIR_TEMPERATURE);
		LocalDateTime now = LocalDateTime.now();
		// An old real reading and a recent simulated one do not count as live data.
		sensorFacade.record(SensorReadingData.builder().sensorCode("SIM-SILENT").value(new BigDecimal("20"))
				.recordedAt(now.minusHours(1)).source(ReadingSource.DEVICE).build());
		sensorFacade.record(SensorReadingData.builder().sensorCode("SIM-SILENT").value(new BigDecimal("21"))
				.recordedAt(now.minusMinutes(1)).source(ReadingSource.SIMULATED).build());
		applicationEvents.clear();

		boolean simulated = simulator(now).simulate(sensor, now);

		assertThat(simulated).isTrue();
		SensorReading stored = readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(sensor.getIdSensor(),
				PageRequest.of(0, 1)).get(0);
		assertThat(stored.getSource()).isEqualTo(ReadingSource.SIMULATED);
		assertThat(stored.getQuality()).isEqualTo("OK");
		assertThat(stored.getUnit()).isEqualTo("°C");
		assertThat(stored.getValue().doubleValue()).isBetween(-5.0, 40.0);
		assertThat(applicationEvents.stream(SensorReadingRecordedEvent.class))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.ownerEmail()).isEqualTo(EMAIL);
					assertThat(event.reading().getSource()).isEqualTo("SIMULATED");
				});
	}

	@Test
	void theSimulatorIsOffByDefault() {
		Sensor sensor = newSensor("SIM-OFF", SensorType.PH);
		VirtualSensorSimulator off = new VirtualSensorSimulator(sensorRepository, readingRepository, sensorFacade,
				false, Duration.ofMinutes(10), Clock.systemDefaultZone(), new Random(1));

		off.tick();

		assertThat(readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(sensor.getIdSensor(),
				PageRequest.of(0, 1))).isEmpty();
	}

	@Test
	void dataSourcesAnswersTheCatalogueOnlyToASignedInUser() throws Exception {
		assertThat(send("GET", "/api/data-sources", null, null).statusCode()).isEqualTo(401);

		HttpResponse<String> answer = send("GET", "/api/data-sources", token(), null);

		assertThat(answer.statusCode()).isEqualTo(200);
		JsonNode body = JSON.readTree(answer.body());
		assertThat(body.path("simulatorEnabled").asBoolean(true)).isFalse();
		assertThat(body.path("readingCountsBySource").path("SEED").asLong()).isPositive();
		assertThat(body.path("readingCountsBySource").has("SIMULATED")).isTrue();
		List<String> keys = new ArrayList<>();
		for (JsonNode entry : body.path("sources")) {
			keys.add(entry.path("key").asString());
			assertThat(entry.path("origin").asString())
					.isIn("SENSOR_FISICO", "SENSOR_VIRTUAL", "ENDPOINT", "IA", "SEED", "CALCULO");
			assertThat(entry.path("producer").asString()).isNotBlank();
			assertThat(Path.of("../..", entry.path("file").asString())).as("file of %s", entry.path("key")).exists();
		}
		assertThat(keys).contains("readings.esp32", "readings.device", "readings.simulated", "readings.manual",
				"readings.seed", "alerts", "predictions", "irrigations", "dashboard", "chat");
	}

	private VirtualSensorSimulator simulator(LocalDateTime now) {
		Clock clock = Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
		return new VirtualSensorSimulator(sensorRepository, readingRepository, sensorFacade, true,
				Duration.ofMinutes(10), clock, new Random(7));
	}

	private Sensor newSensor(String code, SensorType type) {
		return sensorRepository.save(Sensor.builder().crop(anyCrop()).sensorCode(code).sensorType(type).build());
	}

	private Crop anyCrop() {
		return cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(EMAIL).get(0);
	}

	private static SensorReadingResponse firstReading(Map<String, Object> answer) {
		return (SensorReadingResponse) ((List<?>) ((Map<?, ?>) answer.get("event")).get("readings")).get(0);
	}

	private String token() throws Exception {
		HttpResponse<String> login = send("POST", "/api/auth/login", null,
				"{\"email\":\"" + EMAIL + "\",\"password\":\"seed-password\"}");
		return "Bearer " + JSON.readTree(login.body()).path("token").asString();
	}

	private HttpResponse<String> send(String method, String path, String authorization, String jsonBody)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.method(method, jsonBody == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
		if (jsonBody != null) {
			request.header("Content-Type", "application/json");
		}
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}

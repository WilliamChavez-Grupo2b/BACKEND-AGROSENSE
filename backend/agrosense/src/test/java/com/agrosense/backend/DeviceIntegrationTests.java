package com.agrosense.backend;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.agrosense.backend.service.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Field devices over real HTTP, on the schema and sample data from /database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-devices;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password",
		"agrosense.seed.device-key=demo-device-key-for-tests",
		"events.internal-key=internal-key-of-the-events",
		"events.stream-secret=stream-secret-of-at-least-32-bytes",
		"login.max-attempts-per-address=0"
})
@ActiveProfiles("seed")
class DeviceIntegrationTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String PASSWORD = "seed-password";
	private static final String DEMO_DEVICE = "RPI-DEMO-01";
	private static final String DEMO_KEY = "demo-device-key-for-tests";
	private static final String INTERNAL_KEY = "internal-key-of-the-events";
	private static final String STREAM_SECRET = "stream-secret-of-at-least-32-bytes";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private DeviceRepository deviceRepository;
	@Autowired
	private DeviceService deviceService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private EstateRepository estateRepository;
	@Autowired
	private CropRepository cropRepository;
	@Autowired
	private SensorRepository sensorRepository;
	@Autowired
	private SensorReadingRepository readingRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void aDeviceIsRegisteredWithAKeyShownOnceAndStoredOnlyAsItsHash() throws Exception {
		String token = token(EMAIL, PASSWORD);
		Integer cropId = cropOf(EMAIL).getIdCrop();

		HttpResponse<String> created = send("POST", "/api/devices", token,
				"{\"cropId\":" + cropId + ",\"deviceCode\":\" rpi-lote-norte \"}");

		assertThat(created.statusCode()).isEqualTo(201);
		assertThat(created.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		assertThat(created.body()).contains("\"deviceCode\":\"RPI-LOTE-NORTE\"", "\"idCrop\":" + cropId);
		String key = extract(created.body(), "deviceKey");
		assertThat(key).hasSizeGreaterThanOrEqualTo(43);
		Device stored = deviceRepository.findByDeviceCode("RPI-LOTE-NORTE").orElseThrow();
		assertThat(stored.getKeyHash()).isEqualTo(DeviceService.hash(key)).doesNotContain(key).hasSize(64);
		assertThat(deviceService.authenticate("rpi-lote-norte", key)).isPresent();
		assertThat(deviceService.authenticate("RPI-LOTE-NORTE", key + "x")).isEmpty();

		HttpResponse<String> again = send("POST", "/api/devices", token,
				"{\"cropId\":" + cropId + ",\"deviceCode\":\"RPI-LOTE-NORTE\"}");
		assertThat(again.statusCode()).isEqualTo(409);
		assertThat(again.body()).doesNotContain("deviceKey");
	}

	@Test
	void onlyTheCropsOwnerWithAManagingRoleMayRegisterADevice() throws Exception {
		Integer cropId = cropOf(EMAIL).getIdCrop();
		createUser("tecnico@agrosense.test", "technician");
		User other = createUser("otro@agrosense.test", "farmer");
		Crop othersCrop = cropRepository.save(Crop.builder()
				.estate(estateRepository.save(Estate.builder().user(other).name("Ajena").build())).name("Maíz").build());

		assertThat(send("POST", "/api/devices", null, "{\"cropId\":" + cropId + ",\"deviceCode\":\"RPI-X1\"}")
				.statusCode()).isEqualTo(401);
		assertThat(send("POST", "/api/devices", token("tecnico@agrosense.test", "other-password"),
				"{\"cropId\":" + cropId + ",\"deviceCode\":\"RPI-X2\"}").statusCode()).isEqualTo(403);
		assertThat(send("POST", "/api/devices", token(EMAIL, PASSWORD),
				"{\"cropId\":" + othersCrop.getIdCrop() + ",\"deviceCode\":\"RPI-X3\"}").statusCode()).isEqualTo(404);
		assertThat(send("POST", "/api/devices", token(EMAIL, PASSWORD),
				"{\"cropId\":" + cropId + ",\"deviceCode\":\"no vale\"}").statusCode()).isEqualTo(400);
	}

	@Test
	void theDemoDeviceGetsTheKeyFromTheSettingsAndNoUsableHashFromTheScript() {
		assertThat(deviceService.authenticate(DEMO_DEVICE, DEMO_KEY)).isPresent();
		assertThat(deviceService.authenticate(DEMO_DEVICE, "DISABLED")).isEmpty();
		assertThat(deviceRepository.findByDeviceCode(DEMO_DEVICE).orElseThrow().getCrop().getName()).isEqualTo("Café");
	}

	// ---------- events from the event service ----------

	@Test
	void theInternalRouteOnlyLetsInTheEventServiceAndEachEventOnlyWithItsDevicesKey() throws Exception {
		String event = "{\"device_id\":\"" + DEMO_DEVICE + "\",\"type\":\"reading\",\"data\":{\"ph\":6.4}}";

		assertThat(internal(null, DEMO_KEY, event).statusCode()).isEqualTo(401);
		assertThat(internal("another-key", DEMO_KEY, event).statusCode()).isEqualTo(401);
		// A user's token is not a way in either.
		assertThat(send("POST", "/api/internal/events", token(EMAIL, PASSWORD), event).statusCode()).isEqualTo(401);
		HttpResponse<String> wrongDevice = internal(INTERNAL_KEY, "not-the-key", event);
		assertThat(wrongDevice.statusCode()).isEqualTo(401);
		assertThat(wrongDevice.body()).contains("Dispositivo desconocido o llave incorrecta");
		assertThat(internal(INTERNAL_KEY, DEMO_KEY,
				"{\"device_id\":\"RPI-NO-EXISTE\",\"type\":\"reading\",\"data\":{\"ph\":6.4}}").statusCode()).isEqualTo(401);
	}

	@Test
	void aReadingEventIsStoredLikeAnyReadingAndAnsweredWithTheOwnerAndWhatWasStored() throws Exception {
		long before = readingRepository.count();

		HttpResponse<String> answer = internal(INTERNAL_KEY, DEMO_KEY, "{\"device_id\":\"rpi-demo-01\",\"type\":\"reading\","
				+ "\"ts\":\"" + OffsetDateTime.now().minusMinutes(1) + "\","
				+ "\"data\":{\"soil_moisture\":55.5,\"air_temperature\":22.1,\"ph\":9.1}}");

		assertThat(answer.statusCode()).isEqualTo(200);
		JsonNode body = JSON.readTree(answer.body());
		assertThat(body.path("ownerEmail").asString()).isEqualTo(EMAIL);
		JsonNode event = body.path("event");
		assertThat(event.path("type").asString()).isEqualTo("reading");
		assertThat(event.path("deviceId").asString()).isEqualTo(DEMO_DEVICE);
		assertThat(event.path("cropName").asString()).isEqualTo("Café");
		assertThat(event.path("readings")).hasSize(3);
		assertThat(event.path("readings").findValuesAsString("sensorCode")).containsExactly("AS-001", "AS-002", "AS-003");
		// pH 9.1 is outside the coffee's range (5.5–7.0): the same alert a REST or MQTT reading raises.
		assertThat(event.path("alerts").findValuesAsString("alertType")).contains("PH_OUT_OF_RANGE");
		assertThat(readingRepository.count()).isEqualTo(before + 3);
		assertThat(deviceRepository.findByDeviceCode(DEMO_DEVICE).orElseThrow().getLastEventAt()).isNotNull();
	}

	@Test
	void withSeveralSensorsOfTheSameTypeTheDeviceMustSayWhichOne() throws Exception {
		Crop crop = cropRepository.save(Crop.builder().estate(cropOf(EMAIL).getEstate()).name("Fresa").build());
		sensorRepository.save(Sensor.builder().crop(crop).sensorCode("FR-01").sensorType(SensorType.SOIL_MOISTURE).build());
		sensorRepository.save(Sensor.builder().crop(crop).sensorCode("FR-02").sensorType(SensorType.SOIL_MOISTURE).build());
		String key = deviceService.create(EMAIL, crop.getIdCrop(), "RPI-FRESA").getDeviceKey();
		long before = readingRepository.count();

		HttpResponse<String> ambiguous = internal(INTERNAL_KEY, key,
				"{\"device_id\":\"RPI-FRESA\",\"type\":\"reading\",\"data\":{\"soil_moisture\":50}}");

		assertThat(ambiguous.statusCode()).isEqualTo(400);
		assertThat(JSON.readTree(ambiguous.body()).path("message").asString())
				.contains("2 sensores activos de soil_moisture (FR-01, FR-02)", "sensor_code");
		assertThat(readingRepository.count()).isEqualTo(before);

		HttpResponse<String> named = internal(INTERNAL_KEY, key, "{\"device_id\":\"RPI-FRESA\",\"type\":\"reading\","
				+ "\"data\":{\"soil_moisture\":{\"value\":50,\"sensor_code\":\"fr-02\"}}}");
		assertThat(named.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(named.body()).path("event").path("readings").path(0).path("sensorCode").asString())
				.isEqualTo("FR-02");

		// A sensor of another crop, even of the same owner, cannot be named.
		assertThat(internal(INTERNAL_KEY, key, "{\"device_id\":\"RPI-FRESA\",\"type\":\"reading\","
				+ "\"data\":{\"soil_moisture\":{\"value\":50,\"sensor_code\":\"AS-001\"}}}").statusCode()).isEqualTo(400);
	}

	@Test
	void anEventWithOneBadMeasurementStoresNothingAndSaysWhatToFix() throws Exception {
		long before = readingRepository.count();
		Map<String, String> cases = Map.of(
				"{\"soil_moisture\":40,\"wind\":3}", "Medida desconocida 'wind'",
				"{\"soil_moisture\":\"mucha\"}", "debe ser un número",
				"{\"conductivity\":1.2}", "no tiene un sensor activo de conductivity",
				"{}", "al menos una medida");
		for (Map.Entry<String, String> bad : cases.entrySet()) {
			HttpResponse<String> answer = internal(INTERNAL_KEY, DEMO_KEY,
					"{\"device_id\":\"" + DEMO_DEVICE + "\",\"type\":\"reading\",\"data\":" + bad.getKey() + "}");
			assertThat(answer.statusCode()).as(bad.getKey()).isEqualTo(400);
			assertThat(answer.body()).as(bad.getKey()).contains(bad.getValue());
		}
		HttpResponse<String> future = internal(INTERNAL_KEY, DEMO_KEY, "{\"device_id\":\"" + DEMO_DEVICE
				+ "\",\"type\":\"reading\",\"ts\":\"" + OffsetDateTime.now().plusHours(2) + "\",\"data\":{\"ph\":6}}");
		assertThat(future.statusCode()).isEqualTo(400);
		assertThat(internal(INTERNAL_KEY, DEMO_KEY, "{\"device_id\":\"" + DEMO_DEVICE
				+ "\",\"type\":\"riego\",\"data\":{}}").statusCode()).isEqualTo(400);
		assertThat(readingRepository.count()).isEqualTo(before);
	}

	@Test
	void anAlertTheDeviceReportsIsStoredOnceWhileItStaysOpen() throws Exception {
		Crop crop = cropRepository.save(Crop.builder().estate(cropOf(EMAIL).getEstate()).name("Mora").build());
		String key = deviceService.create(EMAIL, crop.getIdCrop(), "RPI-MORA").getDeviceKey();
		String event = "{\"device_id\":\"RPI-MORA\",\"type\":\"alert\",\"ts\":1790000000,"
				+ "\"data\":{\"alert_type\":\"pest_detected\",\"message\":\"Trampa con insectos.\"}}";

		HttpResponse<String> first = internal(INTERNAL_KEY, key, event);
		HttpResponse<String> second = internal(INTERNAL_KEY, key, event);

		assertThat(first.statusCode()).isEqualTo(200);
		JsonNode alert = JSON.readTree(first.body()).path("event").path("alerts").path(0);
		assertThat(alert.path("alertType").asString()).isEqualTo("PEST_DETECTED");
		assertThat(alert.path("severity").asString()).isEqualTo("VERY_HIGH");
		assertThat(JSON.readTree(second.body()).path("event").path("alerts")).isEmpty();
		assertThat(internal(INTERNAL_KEY, key, "{\"device_id\":\"RPI-MORA\",\"type\":\"alert\","
				+ "\"data\":{\"alert_type\":\"granizo\",\"message\":\"x\"}}").statusCode()).isEqualTo(400);
	}

	@Test
	void theStreamTokenNamesTheUserAndLastsFiveMinutes() throws Exception {
		assertThat(send("GET", "/api/events/token", null, null).statusCode()).isEqualTo(401);

		HttpResponse<String> answer = send("GET", "/api/events/token", token(EMAIL, PASSWORD), null);

		assertThat(answer.statusCode()).isEqualTo(200);
		assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		JsonNode body = JSON.readTree(answer.body());
		assertThat(body.path("expiresInSeconds").asLong()).isEqualTo(300);
		Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(STREAM_SECRET.getBytes(StandardCharsets.UTF_8)))
				.build().parseSignedClaims(body.path("token").asString()).getPayload();
		assertThat(claims.getSubject()).isEqualTo(EMAIL);
		assertThat(claims.getAudience()).containsExactly("agrosense-events");
		assertThat(Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()))
				.isEqualTo(Duration.ofMinutes(5));
	}

	// ---------- helpers ----------

	private HttpResponse<String> internal(String internalKey, String deviceKey, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/events"))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json");
		if (internalKey != null) {
			request.header("X-Internal-Key", internalKey);
		}
		if (deviceKey != null) {
			request.header("X-Device-Key", deviceKey);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private Crop cropOf(String email) {
		return cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email).get(0);
	}

	private User createUser(String email, String role) {
		return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
				.name("Otra").lastName("Persona").email(email)
				.passwordHash(passwordEncoder.encode("other-password")).role(role).build()));
	}

	private String token(String email, String password) throws Exception {
		return "Bearer " + extract(send("POST", "/api/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").body(), "token");
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

	private static String extract(String json, String field) {
		Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
		assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
		return matcher.group(1);
	}
}

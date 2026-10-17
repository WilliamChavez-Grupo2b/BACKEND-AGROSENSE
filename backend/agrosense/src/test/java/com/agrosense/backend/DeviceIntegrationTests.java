package com.agrosense.backend;

import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.UserRepository;
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
		"agrosense.seed.device-key=llave-del-dispositivo-demo",
		"login.max-attempts-per-address=0"
})
@ActiveProfiles("seed")
class DeviceIntegrationTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String PASSWORD = "seed-password";
	private static final String DEMO_DEVICE = "RPI-DEMO-01";
	private static final String DEMO_KEY = "llave-del-dispositivo-demo";

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

	// ---------- helpers ----------

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

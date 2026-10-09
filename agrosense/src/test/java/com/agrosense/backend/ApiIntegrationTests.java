package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP and WebSocket calls against the application, on the schema and sample data from /database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"cors.allowed-origins=http://localhost:5173",
		"jwt.secret=test-secret-that-is-at-least-32-bytes-long",
		"agrosense.seed.password=seed-password"
})
@ActiveProfiles("seed")
class ApiIntegrationTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String PASSWORD = "seed-password";

	@Value("${local.server.port}")
	private int port;

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
	private AlertRepository alertRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void onlyHealthAndLoginArePublic() throws Exception {
		assertThat(send("GET", "/api/health", null, null).statusCode()).isEqualTo(200);

		HttpResponse<String> anonymous = send("GET", "/api/dashboard", null, null);
		assertThat(anonymous.statusCode()).isEqualTo(401);
		assertThat(anonymous.body()).contains("Debes iniciar sesión");
		assertThat(send("POST", "/api/readings", null, "{\"sensorCode\":\"AS-001\",\"value\":50}").statusCode())
				.isEqualTo(401);
	}

	@Test
	void loginIssuesATokenAndFailsTheSameWayForEveryWrongCredential() throws Exception {
		HttpResponse<String> wrongPassword = login(EMAIL, "wrong");
		HttpResponse<String> unknownUser = login("nobody@agrosense.test", "wrong");
		assertThat(wrongPassword.statusCode()).isEqualTo(401);
		assertThat(unknownUser.statusCode()).isEqualTo(401);
		assertThat(wrongPassword.body()).isEqualTo(unknownUser.body()).contains("Credenciales incorrectas.");

		assertThat(send("POST", "/api/auth/login", null, "{\"email\":\"not-an-email\",\"password\":\"x\"}").statusCode())
				.isEqualTo(400);

		HttpResponse<String> ok = login("  DEMO@agrosense.co ", PASSWORD);
		assertThat(ok.statusCode()).isEqualTo(200);
		assertThat(ok.body()).contains("\"token\":\"").contains("\"role\":\"farmer\"").doesNotContain("password");
	}

	@Test
	void tokensMustBeValidAndBasicAuthenticationIsNoLongerAccepted() throws Exception {
		String token = token();
		assertThat(send("GET", "/api/dashboard", "Bearer " + token, null).statusCode()).isEqualTo(200);

		String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
		assertThat(send("GET", "/api/dashboard", "Bearer " + tampered, null).statusCode()).isEqualTo(401);
		assertThat(send("GET", "/api/dashboard", "Bearer not-a-token", null).statusCode()).isEqualTo(401);

		String basic = Base64.getEncoder().encodeToString((EMAIL + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
		assertThat(send("GET", "/api/dashboard", "Basic " + basic, null).statusCode()).isEqualTo(401);
	}

	@Test
	void tokensStopWorkingWhenTheAccountIsDisabled() throws Exception {
		User user = createOtherUser("disabled@agrosense.test");
		String token = extract(login(user.getEmail(), "other-password").body(), "token");
		assertThat(send("GET", "/api/dashboard", "Bearer " + token, null).statusCode()).isEqualTo(200);

		user.setActive(false);
		userRepository.save(user);

		assertThat(send("GET", "/api/dashboard", "Bearer " + token, null).statusCode()).isEqualTo(401);
	}

	@Test
	void dashboardSummarisesOnlyTheCallersData() throws Exception {
		createOtherUsersSensor("other-dash@agrosense.test", "OTHER-DASH");

		HttpResponse<String> response = send("GET", "/api/dashboard", "Bearer " + token(), null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("\"totalCrops\":3")
				.contains("\"totalSensors\":5")
				.contains("\"aiRecommendation\":\"")
				.contains("\"sensorCode\":\"AS-00")
				.doesNotContain("OTHER-DASH");
	}

	@Test
	void readingsAreValidatedNormalisedAndRaiseAlertsOutsideTheCropRange() throws Exception {
		String auth = "Bearer " + token();
		Sensor temperature = sensorRepository.findBySensorCode("AS-002").orElseThrow();
		long alertsBefore = alertRepository.count();

		// In range: stored, rounded to two decimals, unit filled in, no alert.
		HttpResponse<String> normal = send("POST", "/api/readings", auth, "{\"sensorCode\":\"as-002\",\"value\":21.456}");
		assertThat(normal.statusCode()).isEqualTo(201);
		assertThat(normal.body()).contains("\"value\":21.46").contains("\"unit\":\"°C\"").contains("\"quality\":\"OK\"");
		assertThat(alertRepository.count()).isEqualTo(alertsBefore);

		// Above the crop maximum (35): one alert, with severity derived from the value.
		assertThat(send("POST", "/api/readings", auth, "{\"sensorCode\":\"AS-002\",\"value\":47}").statusCode())
				.isEqualTo(201);
		Alert raised = alertRepository.findByCropIdCropAndAcknowledgedFalse(temperature.getCrop().getIdCrop()).stream()
				.filter(alert -> alert.getAlertType() == AlertType.HIGH_TEMPERATURE).findFirst().orElseThrow();
		assertThat(raised.getSeverity().name()).isEqualTo("VERY_HIGH");
		assertThat(raised.getMessage()).contains("por encima del máximo");

		// Still out of range: the open alert is not duplicated.
		send("POST", "/api/readings", auth, "{\"sensorCode\":\"AS-002\",\"value\":48}");
		assertThat(alertRepository.count()).isEqualTo(alertsBefore + 1);

		// Physically impossible: stored as OUT_OF_RANGE and never turned into an alert.
		HttpResponse<String> impossible = send("POST", "/api/readings", auth, "{\"sensorCode\":\"AS-003\",\"value\":99}");
		assertThat(impossible.body()).contains("\"quality\":\"OUT_OF_RANGE\"");
		assertThat(alertRepository.count()).isEqualTo(alertsBefore + 1);

		assertThat(sensorRepository.findBySensorCode("AS-002").orElseThrow().getLastReadingAt()).isNotNull();
		HttpResponse<String> latest = send("GET",
				"/api/sensors/" + temperature.getIdSensor() + "/readings?limit=2", auth, null);
		assertThat(latest.statusCode()).isEqualTo(200);
		assertThat(latest.body().split("\"idReading\"")).hasSize(3);
	}

	@Test
	void readingsAreRejectedForInvalidUnknownInactiveOrForeignSensors() throws Exception {
		String auth = "Bearer " + token();
		Sensor foreign = createOtherUsersSensor("other-read@agrosense.test", "OTHER-READ");
		long before = readingRepository.count();

		assertThat(send("POST", "/api/readings", auth, "{\"sensorCode\":\"\",\"value\":1}").statusCode()).isEqualTo(400);
		assertThat(send("POST", "/api/readings", auth, "{\"sensorCode\":\"AS-001\"}").statusCode()).isEqualTo(400);
		assertThat(send("POST", "/api/readings", auth, "{not json").statusCode()).isEqualTo(400);
		assertThat(send("POST", "/api/readings", auth, "{\"sensorCode\":\"NOPE\",\"value\":1}").statusCode()).isEqualTo(404);
		assertThat(send("POST", "/api/readings", auth, "{\"sensorCode\":\"OTHER-READ\",\"value\":1}").statusCode())
				.isEqualTo(404);
		HttpResponse<String> inactive = send("POST", "/api/readings", auth, "{\"sensorCode\":\"AS-006\",\"value\":1}");
		assertThat(inactive.statusCode()).isEqualTo(409);
		assertThat(inactive.body()).contains("inactivo");
		assertThat(send("GET", "/api/sensors/" + foreign.getIdSensor() + "/readings", auth, null).statusCode())
				.isEqualTo(404);

		assertThat(readingRepository.count()).isEqualTo(before);
	}

	@Test
	void alertsCanBeListedAndAcknowledgedOnlyByTheirOwner() throws Exception {
		String auth = "Bearer " + token();
		Sensor foreign = createOtherUsersSensor("other-alert@agrosense.test", "OTHER-ALERT");
		Alert foreignAlert = alertRepository.save(Alert.builder().crop(foreign.getCrop())
				.alertType(AlertType.WATER_STRESS).message("Private alert").build());

		HttpResponse<String> list = send("GET", "/api/alerts", auth, null);
		assertThat(list.statusCode()).isEqualTo(200);
		assertThat(list.body()).contains("PEST_DETECTED").doesNotContain("Private alert");

		assertThat(send("PATCH", "/api/alerts/" + foreignAlert.getIdAlert() + "/acknowledge", auth, null).statusCode())
				.isEqualTo(404);
		assertThat(alertRepository.findById(foreignAlert.getIdAlert()).orElseThrow().getAcknowledged()).isFalse();

		String ownId = extractNumber(list.body(), "idAlert");
		HttpResponse<String> acknowledged = send("PATCH", "/api/alerts/" + ownId + "/acknowledge", auth, null);
		assertThat(acknowledged.statusCode()).isEqualTo(200);
		assertThat(acknowledged.body()).contains("\"acknowledged\":true");
	}

	@Test
	void webSocketRequiresATokenAndDeliversReadingsOnlyToTheirOwner() throws Exception {
		String token = token();
		createOtherUsersSensor("other-ws@agrosense.test", "OTHER-WS");
		String otherToken = extract(login("other-ws@agrosense.test", "other-password").body(), "token");

		assertThatThrownBy(() -> connect(null)).isInstanceOf(ExecutionException.class);
		assertThatThrownBy(() -> connect("Bearer not-a-token")).isInstanceOf(ExecutionException.class);

		BlockingQueue<String> ownerMessages = subscribe(connect("Bearer " + token), "/user/queue/readings");
		BlockingQueue<String> otherMessages = subscribe(connect("Bearer " + otherToken), "/user/queue/readings");
		Thread.sleep(500);

		send("POST", "/api/readings", "Bearer " + token, "{\"sensorCode\":\"AS-004\",\"value\":61.5}");

		String received = ownerMessages.poll(5, TimeUnit.SECONDS);
		assertThat(received).isNotNull().contains("\"sensorCode\":\"AS-004\"").contains("61.5");
		assertThat(otherMessages.poll(1, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void webSocketClientsCannotSubscribeToSharedTopicsOrPublish() throws Exception {
		StompSession session = connect("Bearer " + token());
		BlockingQueue<String> shared = subscribe(session, "/queue/readings");
		Thread.sleep(300);

		send("POST", "/api/readings", "Bearer " + token(), "{\"sensorCode\":\"AS-005\",\"value\":1.2}");

		assertThat(shared.poll(1, TimeUnit.SECONDS)).isNull();
	}

	// ---------- helpers ----------

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

	private HttpResponse<String> login(String email, String password) throws Exception {
		return send("POST", "/api/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private String token() throws Exception {
		return extract(login(EMAIL, PASSWORD).body(), "token");
	}

	private static String extract(String json, String field) {
		Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
		assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
		return matcher.group(1);
	}

	private static String extractNumber(String json, String field) {
		Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
		assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
		return matcher.group(1);
	}

	private StompSession connect(String authorization) throws Exception {
		WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		// The server sends JSON; reading the raw bytes avoids depending on a JSON converter here.
		stompClient.setMessageConverter(new ByteArrayMessageConverter());
		StompHeaders connectHeaders = new StompHeaders();
		if (authorization != null) {
			connectHeaders.add("Authorization", authorization);
		}
		return stompClient.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
				new StompSessionHandlerAdapter() {
				}).get(5, TimeUnit.SECONDS);
	}

	private static BlockingQueue<String> subscribe(StompSession session, String destination) {
		BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		session.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return byte[].class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				messages.add(new String((byte[]) payload, StandardCharsets.UTF_8));
			}
		});
		return messages;
	}

	private User createOtherUser(String email) {
		return userRepository.save(User.builder()
				.name("Other")
				.lastName("Farmer")
				.email(email)
				.passwordHash(passwordEncoder.encode("other-password"))
				.role("farmer")
				.build());
	}

	private Sensor createOtherUsersSensor(String email, String sensorCode) {
		Estate estate = estateRepository.save(Estate.builder().user(createOtherUser(email)).name("Private estate").build());
		Crop crop = cropRepository.save(Crop.builder().estate(estate).name("Private crop").build());
		return sensorRepository.save(Sensor.builder()
				.crop(crop)
				.sensorCode(sensorCode)
				.sensorType(SensorType.SOIL_MOISTURE)
				.build());
	}
}

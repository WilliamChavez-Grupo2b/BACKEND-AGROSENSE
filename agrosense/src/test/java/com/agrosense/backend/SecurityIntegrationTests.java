package com.agrosense.backend;

import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-security;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"cors.allowed-origins=http://localhost:5173"
})
class SecurityIntegrationTests {

	private static final String EMAIL = "farmer@agrosense.test";
	private static final String PASSWORD = "correct-password";

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private final HttpClient client = HttpClient.newHttpClient();

	@BeforeEach
	void createUser() {
		if (!userRepository.existsByEmail(EMAIL)) {
			userRepository.save(User.builder()
					.name("Test")
					.lastName("Farmer")
					.email(EMAIL)
					.passwordHash(passwordEncoder.encode(PASSWORD))
					.role("farmer")
					.build());
		}
	}

	@Test
	void rejectsAnonymousRequests() throws Exception {
		assertEquals(401, get(null, null).statusCode());
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertEquals("{\"status\":\"UP\"}", response.body());
	}

	@Test
	void rejectsWrongPassword() throws Exception {
		assertEquals(401, get(basic(EMAIL, "wrong-password"), null).statusCode());
	}

	@Test
	void acceptsValidCredentials() throws Exception {
		// No controller is mapped yet, so an authenticated request reaches the dispatcher and gets 404.
		assertEquals(404, get(basic(EMAIL, PASSWORD), null).statusCode());
	}

	@Test
	void onlyAllowsConfiguredCorsOrigins() throws Exception {
		HttpResponse<String> allowed = get(basic(EMAIL, PASSWORD), "http://localhost:5173");
		assertEquals("http://localhost:5173",
				allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

		HttpResponse<String> blocked = get(basic(EMAIL, PASSWORD), "http://evil.example");
		assertEquals(403, blocked.statusCode());
		assertNull(blocked.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
	}

	private HttpResponse<String> get(String authorization, String origin) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ping"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		if (origin != null) {
			request.header("Origin", origin);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String basic(String email, String password) {
		String token = Base64.getEncoder()
				.encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
		return "Basic " + token;
	}
}

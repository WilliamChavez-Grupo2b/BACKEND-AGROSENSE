package com.agrosense.backend;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
import com.agrosense.backend.ai.service.AiServiceClient;
import com.agrosense.backend.ai.service.ChatModel;
import com.agrosense.backend.ai.service.FarmContextService;
import com.agrosense.backend.ai.service.FarmSnapshot;
import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceGatewayTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String OTHER = "otra@agrosense.co";
	private static final String KEY = "test-internal-key";
	private static final String TOKEN = "previous-token";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private HttpServer service;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> answer = new AtomicReference<>("{\"reply\":\"Riega el café hoy.\",\"source\":\"model\"}");
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final List<String> keys = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private final List<String> contentTypes = new CopyOnWriteArrayList<>();
	private final FarmContextService farm = mock(FarmContextService.class);

	@BeforeEach
	void startService() throws IOException {
		service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		service.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().getPath());
			keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Key")));
			authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
			byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		service.start();
		when(farm.snapshot(EMAIL)).thenReturn(new FarmSnapshot(1, 2, 1,
				List.of(new CropStatus("Café", new BigDecimal("31.5000"), new BigDecimal("40.0"), new BigDecimal("80.0"),
						null, null, null, null, null, null)),
				List.of(new OpenAlert("Café", "Humedad baja."))));
		when(farm.snapshot(OTHER)).thenReturn(new FarmSnapshot(1, 1, 0,
				List.of(new CropStatus("Arroz", new BigDecimal("90"), null, null, null, null, null, null, null, null)),
				List.of()));
	}

	@AfterEach
	void stopService() {
		service.stop(0);
	}

	@Test
	void aQuestionGoesToTheServiceWithTheKeyTheTurnsAndOnlyTheCallersFarm() {
		AiRequestDto request = question("  ¿Riego hoy?  ");
		request.setHistory(List.of(turn("user", "Hola"), turn("assistant", "Hola, ¿en qué te ayudo?")));

		AiResponseDto response = gateway(true).chat(EMAIL, request);

		assertThat(response.reply()).isEqualTo("Riega el café hoy.");
		assertThat(response.source()).isEqualTo("model");
		assertThat(paths).containsExactly("/chat");
		assertThat(keys).containsExactly(KEY);
		assertThat(authorizations).containsExactly("Bearer " + TOKEN);
		JsonNode sent = JSON.readTree(new String(bodies.get(0).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
		assertThat(sent.path("message").asString()).isEqualTo("¿Riego hoy?");
		assertThat(sent.path("history")).hasSize(2);
		assertThat(sent.path("history").path(1).path("role").asString()).isEqualTo("assistant");
		assertThat(sent.path("farm_context").asString()).startsWith("Fecha y hora actual:")
				.contains("Cultivo Café: humedad del suelo 31.5 % (rango 40–80 %).", "Alerta en Café: Humedad baja.")
				.doesNotContain("Arroz");
		verify(farm, never()).snapshot(OTHER);
	}

	@Test
	void onlyTheLastEightTurnsAreSent() {
		AiRequestDto request = question("¿Y ahora?");
		List<AiRequestDto.Turn> history = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			history.add(turn(index % 2 == 0 ? "user" : "assistant", "turno " + index));
		}
		request.setHistory(history);

		gateway(true).chat(EMAIL, request);

		JsonNode sent = JSON.readTree(bodies.get(0));
		assertThat(sent.path("history")).hasSize(AiAssistantService.MAX_HISTORY_SENT);
		assertThat(sent.path("history").path(0).path("text").asString()).isEqualTo("turno 4");
	}

	@Test
	void whenTheServiceCannotAnswerTheFarmsFiguresDo() {
		for (int failure : new int[] {503, 429, 502, 504, 500, 401}) {
			status.set(failure);
			answer.set("{\"message\":\"El asistente de IA no está configurado todavía.\"}");

			AiResponseDto response = gateway(true).chat(EMAIL, question("¿Cómo está el café?"));

			assertThat(response.source()).as("status %d", failure).isEqualTo("local");
			assertThat(response.reply()).contains("Café");
		}
	}

	@Test
	void aServiceThatCannotBeReachedIsAnsweredLocally() throws IOException {
		AiAssistantService assistant = assistantWith(new AiServiceClient("http://127.0.0.1:" + closedPort(), KEY, "", 2000), true);

		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
	}

	@Test
	void withoutLocalAnswersAnUnavailableServiceIsA503AndThe429StaysForTheUsersOwnLimit() {
		for (int failure : new int[] {503, 429, 504}) {
			status.set(failure);
			answer.set("{\"message\":\"no disponible\"}");

			assertStatus(() -> gateway(false).chat(EMAIL, question("¿Riego?")), HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	@Test
	void aRefusalOfTheModelIsPassedOnAsItCame() {
		status.set(422);
		answer.set("{\"message\":\"No puedo responder a esa consulta. Prueba a formularla de otra manera.\"}");

		assertThatThrownBy(() -> gateway(true).chat(EMAIL, question("algo")))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus().value()).isEqualTo(422);
					assertThat(error.getMessage()).startsWith("No puedo responder");
				});
	}

	@Test
	void theServiceWinsOverTheAssistantsOwnProviders() {
		AtomicInteger ownCalls = new AtomicInteger();
		ChatModel own = (instructions, conversation) -> {
			ownCalls.incrementAndGet();
			return "respuesta interna";
		};
		AiAssistantService assistant = new AiAssistantService(farm, JSON, Clock.systemUTC(), "a-gemini-key", "g",
				"http://127.0.0.1:9", 2000, 256, 10, true, own, client());

		assertThat(assistant.chat(EMAIL, question("¿Riego?")).reply()).isEqualTo("Riega el café hoy.");
		assertThat(ownCalls.get()).isZero();

		AiAssistantService withoutService = new AiAssistantService(farm, JSON, Clock.systemUTC(), "", "g",
				"http://127.0.0.1:9", 2000, 256, 10, true, own, null);
		assertThat(withoutService.chat(EMAIL, question("¿Riego?")).reply()).isEqualTo("respuesta interna");
	}

	@Test
	void theAssistantMovesToTheServiceOnlyWhenTheFlagSaysSo() {
		String url = "http://127.0.0.1:" + service.getAddress().getPort();

		assertThat(AiAssistantService.chosenService(false, url, KEY, "", 1000)).isNull();
		assertThat(AiAssistantService.chosenService(true, " ", KEY, "", 1000)).isNull();
		assertThat(AiAssistantService.chosenService(true, url, KEY, "", 1000)).isNotNull();
	}

	@Test
	void theUsersLimitOfQuestionsStillApplies() {
		AiAssistantService assistant = new AiAssistantService(farm, JSON, Clock.systemUTC(), "", "g",
				"http://127.0.0.1:9", 2000, 256, 2, true, null, client());

		assistant.chat(EMAIL, question("uno"));
		assistant.chat(EMAIL, question("dos"));

		assertStatus(() -> assistant.chat(EMAIL, question("tres")), HttpStatus.TOO_MANY_REQUESTS);
		assertThat(paths).hasSize(2);
	}

	@Test
	void aRecordingGoesToTheServiceWithTheFarmAndComesBackWithItsTranscript() {
		answer.set("{\"reply\":\"El pH está bien.\",\"transcript\":\"¿Cómo está el pH?\",\"source\":\"model\"}");

		AiResponseDto response = gateway(true).voice(EMAIL, "test-audio".getBytes(StandardCharsets.UTF_8),
				"audio/webm;codecs=opus", null);

		assertThat(response.reply()).isEqualTo("El pH está bien.");
		assertThat(response.transcript()).isEqualTo("¿Cómo está el pH?");
		assertThat(paths).containsExactly("/voice");
		assertThat(keys).containsExactly(KEY);
		assertThat(contentTypes.get(0)).startsWith("multipart/form-data");
		assertThat(bodies.get(0)).contains("name=\"audio\"", "Content-Type: audio/webm", "test-audio",
				"name=\"farm_context\"");
	}

	@Test
	void aRecordingTheServiceCannotHearIsA503WithItsMessage() {
		status.set(503);
		answer.set("{\"message\":\"Ahora no puedo escuchar mensajes de voz. Escribe tu pregunta y te respondo.\"}");

		assertThatThrownBy(() -> gateway(true).voice(EMAIL, new byte[] {1, 2, 3}, "audio/webm", null))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.getMessage()).contains("Escribe tu pregunta");
				});
	}

	@Test
	void aTranscriptIsAskedAsAWrittenQuestion() {
		AiResponseDto response = gateway(true).voice(EMAIL, null, null, "¿Riego hoy?");

		assertThat(response.transcript()).isEqualTo("¿Riego hoy?");
		assertThat(paths).containsExactly("/chat");
	}

	private AiServiceClient client() {
		return new AiServiceClient("http://127.0.0.1:" + service.getAddress().getPort() + "/", KEY, TOKEN, 2000);
	}

	private AiAssistantService gateway(boolean localAnswers) {
		return assistantWith(client(), localAnswers);
	}

	private AiAssistantService assistantWith(AiServiceClient client, boolean localAnswers) {
		return new AiAssistantService(farm, JSON, Clock.systemUTC(), "", "g", "http://127.0.0.1:9", 2000, 256, 10,
				localAnswers, null, client);
	}

	private static int closedPort() throws IOException {
		try (ServerSocket probe = new ServerSocket(0)) {
			return probe.getLocalPort();
		}
	}

	private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
		assertThatThrownBy(call).isInstanceOfSatisfying(AiException.class,
				error -> assertThat(error.getStatus()).isEqualTo(expected));
	}

	private static AiRequestDto question(String message) {
		AiRequestDto request = new AiRequestDto();
		request.setMessage(message);
		return request;
	}

	private static AiRequestDto.Turn turn(String role, String text) {
		AiRequestDto.Turn turn = new AiRequestDto.Turn();
		turn.setRole(role);
		turn.setText(text);
		return turn;
	}
}

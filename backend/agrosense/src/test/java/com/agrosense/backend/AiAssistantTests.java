package com.agrosense.backend;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.service.DashboardService;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The assistant against a local server that plays the part of Gemini; no request leaves the machine. */
class AiAssistantTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String KEY = "test-key";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private HttpServer gemini;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> answer = new AtomicReference<>(candidate("Riega 20 minutos al amanecer."));
	private final AtomicReference<String> receivedBody = new AtomicReference<>();
	private final AtomicReference<String> receivedKey = new AtomicReference<>();
	private final AtomicReference<String> receivedPath = new AtomicReference<>();
	private final AtomicInteger calls = new AtomicInteger();
	private final DashboardService dashboard = mock(DashboardService.class);

	@BeforeEach
	void startGemini() throws IOException {
		gemini = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		gemini.createContext("/", exchange -> {
			calls.incrementAndGet();
			receivedPath.set(exchange.getRequestURI().toString());
			receivedKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
			receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		gemini.start();
		when(dashboard.build(EMAIL)).thenReturn(DashboardResponse.builder()
				.totalCrops(2).totalSensors(5).pendingAlerts(1)
				.lastHumidity(new BigDecimal("31.5000")).lastTemperature(new BigDecimal("24.0"))
				.activeAlerts(List.of(AlertResponse.builder().cropName("Café").message("Humedad baja.").build()))
				.build());
	}

	@AfterEach
	void stopGemini() {
		gemini.stop(0);
	}

	@Test
	void aQuestionTravelsWithTheSystemPromptTheFarmFiguresAndTheEarlierTurns() {
		AiRequestDto request = question("  ¿Cuándo riego el café?  ");
		request.setHistory(List.of(turn("user", "Hola"), turn("assistant", "Hola, ¿en qué te ayudo?")));

		AiResponseDto response = assistant(KEY, 10, Clock.systemUTC()).chat(EMAIL, request);

		assertThat(response.reply()).isEqualTo("Riega 20 minutos al amanecer.");
		assertThat(response.transcript()).isNull();
		// The key goes in a header and never in the address, where it would end up in logs.
		assertThat(receivedKey.get()).isEqualTo(KEY);
		assertThat(receivedPath.get()).isEqualTo("/v1beta/models/test-model:generateContent").doesNotContain(KEY);
		JsonNode sent = JSON.readTree(receivedBody.get());
		assertThat(sent.path("system_instruction").path("parts").path(0).path("text").asString())
				.contains("AgroSense IA");
		assertThat(sent.path("system_instruction").path("parts").path(1).path("text").asString())
				.contains("2 cultivos", "5 sensores", "1 alertas", "suelo 31.5 %", "Temperatura 24 °C")
				.endsWith("Café: Humedad baja.").doesNotContain("pH");
		JsonNode contents = sent.path("contents");
		assertThat(contents).hasSize(3);
		assertThat(contents.path(1).path("role").asString()).isEqualTo("model");
		assertThat(contents.path(2).path("role").asString()).isEqualTo("user");
		assertThat(contents.path(2).path("parts").path(0).path("text").asString()).isEqualTo("¿Cuándo riego el café?");
		assertThat(sent.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(256);
	}

	@Test
	void withoutAKeyTheAssistantSaysItIsNotConfiguredAndCallsNobody() {
		assertThatThrownBy(() -> assistant(" ", 10, Clock.systemUTC()).chat(EMAIL, question("Hola")))
				.isInstanceOfSatisfying(AiException.class,
						error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(calls.get()).isZero();
	}

	@Test
	void failuresOfGeminiBecomeAnswersTheUserCanRead() {
		AiAssistantService assistant = assistant(KEY, 0, Clock.systemUTC());

		status.set(429);
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.TOO_MANY_REQUESTS);
		status.set(500);
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.BAD_GATEWAY);
		// A key Gemini refuses is a fault of this server's settings, not of the request.
		status.set(403);
		answer.set("{\"error\":{\"message\":\"API key not valid: " + KEY + "\"}}");
		assertThatThrownBy(() -> assistant.chat(EMAIL, question("Hola")))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(error.getMessage()).doesNotContain(KEY);
				});

		status.set(200);
		answer.set("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.UNPROCESSABLE_ENTITY);
		answer.set("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}");
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.BAD_GATEWAY);
	}

	@Test
	void aServerThatDoesNotAnswerInTimeIsReportedAsATimeout() {
		gemini.stop(0);
		assertStatus(() -> assistant(KEY, 0, Clock.systemUTC()).chat(EMAIL, question("Hola")),
				HttpStatus.GATEWAY_TIMEOUT);
	}

	@Test
	void eachUserMayAskALimitedNumberOfQuestionsPerMinute() {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T12:00:00Z"));
		Clock clock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		when(dashboard.build("other@agrosense.co")).thenReturn(DashboardResponse.builder().build());
		AiAssistantService assistant = assistant(KEY, 2, clock);

		assistant.chat(EMAIL, question("Uno"));
		assistant.chat(EMAIL, question("Dos"));
		assertStatus(() -> assistant.chat(EMAIL, question("Tres")), HttpStatus.TOO_MANY_REQUESTS);
		assertThat(calls.get()).isEqualTo(2);
		// The limit is per user, and it lifts once the minute has passed.
		assistant.chat("other@agrosense.co", question("Uno"));
		now.set(now.get().plus(Duration.ofSeconds(61)));
		assistant.chat(EMAIL, question("Tres"));
		assertThat(calls.get()).isEqualTo(4);

		// Users who stopped asking are forgotten, so the count does not grow with every account.
		assistant.forgetIdleUsers();
		assertThat(assistant.trackedUsers()).isEqualTo(1);
		now.set(now.get().plus(Duration.ofSeconds(61)));
		assistant.forgetIdleUsers();
		assertThat(assistant.trackedUsers()).isZero();
	}

	@Test
	void theConversationSentAlwaysStartsWithTheUserAndAlternates() {
		AiRequestDto request = question("¿Y mañana?");
		// What a browser may keep: an answer whose question was dropped, and questions left unanswered.
		request.setHistory(List.of(turn("assistant", "Hola"), turn("user", "¿Riego hoy?"),
				turn("user", "¿Sigues ahí?"), turn("assistant", "Sí, riega hoy."), turn("user", "Gracias")));

		assistant(KEY, 10, Clock.systemUTC()).chat(EMAIL, request);

		JsonNode contents = JSON.readTree(receivedBody.get()).path("contents");
		assertThat(contents).hasSize(3);
		assertThat(contents.path(0).path("role").asString()).isEqualTo("user");
		assertThat(contents.path(0).path("parts").path(0).path("text").asString()).isEqualTo("¿Riego hoy?\n¿Sigues ahí?");
		assertThat(contents.path(1).path("role").asString()).isEqualTo("model");
		assertThat(contents.path(2).path("role").asString()).isEqualTo("user");
		assertThat(contents.path(2).path("parts").path(0).path("text").asString()).isEqualTo("Gracias\n¿Y mañana?");
	}

	@Test
	void aRecordingIsSentAsAudioAndAnsweredWithWhatWasHeard() {
		answer.set(candidate("{\\\"transcript\\\":\\\"¿Cómo está el suelo?\\\",\\\"reply\\\":\\\"Está seco: riega hoy.\\\"}"));
		byte[] audio = {1, 2, 3, 4};

		AiResponseDto response = assistant(KEY, 10, Clock.systemUTC())
				.voice(EMAIL, audio, "audio/webm;codecs=opus", null);

		assertThat(response.reply()).isEqualTo("Está seco: riega hoy.");
		assertThat(response.transcript()).isEqualTo("¿Cómo está el suelo?");
		JsonNode sent = JSON.readTree(receivedBody.get());
		JsonNode inline = sent.path("contents").path(0).path("parts").path(0).path("inline_data");
		assertThat(inline.path("mime_type").asString()).isEqualTo("audio/webm");
		assertThat(inline.path("data").asString()).isEqualTo(Base64.getEncoder().encodeToString(audio));
		assertThat(sent.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");

		// An answer that ignores the requested shape is still shown rather than lost.
		answer.set(candidate("Riega hoy."));
		assertThat(assistant(KEY, 10, Clock.systemUTC()).voice(EMAIL, audio, "audio/ogg", null).reply())
				.isEqualTo("Riega hoy.");
	}

	@Test
	void aTranscriptIsAnsweredAsTextAndBadRecordingsAreRefusedBeforeCallingGemini() {
		AiAssistantService assistant = assistant(KEY, 10, Clock.systemUTC());

		AiResponseDto response = assistant.voice(EMAIL, new byte[] {9}, "audio/webm", " ¿Riego hoy? ");
		assertThat(response.transcript()).isEqualTo("¿Riego hoy?");
		assertThat(receivedBody.get()).contains("¿Riego hoy?").doesNotContain("inline_data");
		assertThat(calls.get()).isEqualTo(1);

		assertStatus(() -> assistant.voice(EMAIL, null, null, "  "), HttpStatus.BAD_REQUEST);
		assertStatus(() -> assistant.voice(EMAIL, new byte[0], "audio/webm", null), HttpStatus.BAD_REQUEST);
		assertStatus(() -> assistant.voice(EMAIL, new byte[] {1}, "video/mp4", null), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertStatus(() -> assistant.voice(EMAIL, new byte[] {1}, null, null), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertStatus(() -> assistant.voice(EMAIL, new byte[AiAssistantService.MAX_AUDIO_BYTES + 1], "audio/webm", null),
				HttpStatus.PAYLOAD_TOO_LARGE);
		assertStatus(() -> assistant.voice(EMAIL, null, null, "a".repeat(AiRequestDto.MAX_MESSAGE_LENGTH + 1)),
				HttpStatus.BAD_REQUEST);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void theAssistantStillAnswersWhenTheFarmFiguresCannotBeRead() {
		when(dashboard.build(EMAIL)).thenThrow(new IllegalStateException("database down"));

		assertThat(assistant(KEY, 10, Clock.systemUTC()).chat(EMAIL, question("Hola")).reply())
				.isEqualTo("Riega 20 minutos al amanecer.");
		assertThat(receivedBody.get()).contains("No hay datos de la finca").doesNotContain("database down");
	}

	// ---------- helpers ----------

	private AiAssistantService assistant(String key, int requestsPerMinute, Clock clock) {
		return new AiAssistantService(dashboard, JSON, clock, key, "test-model",
				"http://127.0.0.1:" + gemini.getAddress().getPort(), 2000, 256, requestsPerMinute);
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

	/** A Gemini answer whose only candidate says {@code text} (already escaped for JSON). */
	private static String candidate(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
	}
}

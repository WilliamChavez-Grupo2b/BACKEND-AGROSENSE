package com.agrosense.backend;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
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
	private final FarmContextService farm = mock(FarmContextService.class);

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
		when(farm.snapshot(EMAIL)).thenReturn(new FarmSnapshot(2, 5, 1,
				List.of(new CropStatus("Café", new BigDecimal("31.5000"), new BigDecimal("40.0"), new BigDecimal("80.0"),
								new BigDecimal("24.0"), new BigDecimal("15.0"), new BigDecimal("35.0"),
								null, new BigDecimal("5.5"), new BigDecimal("7.0")),
						new CropStatus("Papa", null, new BigDecimal("40.0"), new BigDecimal("80.0"),
								null, new BigDecimal("15.0"), new BigDecimal("35.0"),
								null, new BigDecimal("5.5"), new BigDecimal("7.0"))),
				List.of(new OpenAlert("Café", "Humedad baja."))));
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
				.contains("2 cultivos", "5 sensores", "1 alertas",
						"Cultivo Café: humedad del suelo 31.5 % (rango 40–80 %), temperatura 24 °C (rango 15–35 °C).",
						"Cultivo Papa: sin lecturas.")
				.endsWith("Alerta en Café: Humedad baja.").doesNotContain("pH");
		assertThat(response.source()).isEqualTo("model");
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
	void withoutAKeyAWrittenQuestionIsAnsweredFromTheFarmsFigures() {
		AiResponseDto response = answeringOnItsOwn(" ", 10).chat(EMAIL, question("¿Cuándo conviene regar?"));

		assertThat(response.source()).isEqualTo("local");
		assertThat(response.reply()).contains("**Café**: riega. La humedad (31.5 %) está por debajo de su mínimo (40 %).",
				"**Papa**: no tiene lecturas de humedad");
		assertThat(calls.get()).isZero();
	}

	@Test
	void whenTheModelFailsTheQuestionIsStillAnsweredAndARefusalStaysARefusal() {
		AiAssistantService assistant = answeringOnItsOwn(KEY, 0);

		for (int failure : new int[] {429, 500, 403}) {
			status.set(failure);
			AiResponseDto response = assistant.chat(EMAIL, question("¿Qué alertas tengo?"));
			assertThat(response.source()).isEqualTo("local");
			assertThat(response.reply()).startsWith("Tienes 1 alerta sin atender:").contains("**Café**: Humedad baja.");
		}
		status.set(200);
		answer.set("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}");
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
		// The model declining a question is an answer about the question, not a fault to work around.
		answer.set("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.UNPROCESSABLE_ENTITY);

		gemini.stop(0);
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
	}

	@Test
	void answersGivenWithoutTheModelCountTowardsTheLimitAndVoiceNeedsTheModelToListen() {
		AiAssistantService assistant = answeringOnItsOwn("", 2);

		// What was said, already as text, is answered like any written question.
		AiResponseDto spoken = assistant.voice(EMAIL, null, null, " ¿Riego hoy? ");
		assertThat(spoken.transcript()).isEqualTo("¿Riego hoy?");
		assertThat(spoken.source()).isEqualTo("local");
		assertThat(spoken.reply()).contains("**Café**: riega");
		assistant.chat(EMAIL, question("Dos"));
		assertStatus(() -> assistant.chat(EMAIL, question("Tres")), HttpStatus.TOO_MANY_REQUESTS);

		assertThatThrownBy(() -> assistant.voice(EMAIL, new byte[] {1}, "audio/webm", null))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.getMessage()).contains("Escribe tu pregunta");
				});
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
		when(farm.snapshot("other@agrosense.co")).thenReturn(new FarmSnapshot(0, 0, 0, List.of(), List.of()));
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
		when(farm.snapshot(EMAIL)).thenThrow(new IllegalStateException("database down"));

		assertThat(assistant(KEY, 10, Clock.systemUTC()).chat(EMAIL, question("Hola")).reply())
				.isEqualTo("Riega 20 minutos al amanecer.");
		assertThat(receivedBody.get()).contains("No hay datos de la finca").doesNotContain("database down");
	}

	// ---------- helpers ----------

	/** An assistant that refuses a question when the model cannot answer it, to see each refusal. */
	private AiAssistantService assistant(String key, int requestsPerMinute, Clock clock) {
		return assistant(key, requestsPerMinute, clock, false);
	}

	/** The assistant as it runs: without the model it answers from the farm's figures. */
	private AiAssistantService answeringOnItsOwn(String key, int requestsPerMinute) {
		return assistant(key, requestsPerMinute, Clock.systemUTC(), true);
	}

	private AiAssistantService assistant(String key, int requestsPerMinute, Clock clock, boolean localAnswers) {
		return new AiAssistantService(farm, JSON, clock, key, "test-model",
				"http://127.0.0.1:" + gemini.getAddress().getPort(), 2000, 256, requestsPerMinute, localAnswers);
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

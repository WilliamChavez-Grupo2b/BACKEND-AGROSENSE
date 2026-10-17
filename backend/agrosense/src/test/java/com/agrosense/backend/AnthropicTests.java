package com.agrosense.backend;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
import com.agrosense.backend.ai.service.AnthropicClient;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String KEY = "test-anthropic-key";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private HttpServer anthropic;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> answer = new AtomicReference<>(message("El **café** necesita riego hoy.", "end_turn"));
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final List<String> keys = new CopyOnWriteArrayList<>();
	private final List<String> versions = new CopyOnWriteArrayList<>();
	private final FarmContextService farm = mock(FarmContextService.class);

	@BeforeEach
	void startAnthropic() throws IOException {
		anthropic = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		anthropic.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().toString());
			keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
			versions.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-version")));
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		anthropic.start();
		when(farm.snapshot(EMAIL)).thenReturn(new FarmSnapshot(1, 2, 1,
				List.of(new CropStatus("Café", new BigDecimal("31.5000"), new BigDecimal("40.0"), new BigDecimal("80.0"),
						null, new BigDecimal("15.0"), new BigDecimal("35.0"),
						null, new BigDecimal("5.5"), new BigDecimal("7.0"))),
				List.of(new OpenAlert("Café", "Humedad baja."))));
	}

	@AfterEach
	void stopAnthropic() {
		anthropic.stop(0);
	}

	@Test
	void aQuestionReachesClaudeWithItsHeadersThePromptTheFarmAndTheConversation() {
		answer.set("{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":["
				+ "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"},"
				+ "{\"type\":\"text\",\"text\":\"El **café** necesita \"},{\"type\":\"text\",\"text\":\"riego hoy.\"}]}");
		AiRequestDto request = question(" ¿Y mañana? ");
		request.setHistory(List.of(turn("assistant", "Hola"), turn("user", "¿Riego hoy?"),
				turn("assistant", "Sí, riega hoy.")));

		AiResponseDto response = assistant(true).chat(EMAIL, request);

		assertThat(response.reply()).isEqualTo("El **café** necesita riego hoy.");
		assertThat(response.source()).isEqualTo("model");
		assertThat(paths).containsExactly("/v1/messages");
		assertThat(keys).containsExactly(KEY);
		assertThat(versions).containsExactly("2023-06-01");
		JsonNode sent = JSON.readTree(bodies.get(0));
		assertThat(sent.path("model").asString()).isEqualTo("claude-test");
		assertThat(sent.path("max_tokens").asInt()).isEqualTo(256);
		assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("low");
		assertThat(sent.has("temperature")).isFalse();
		assertThat(sent.path("system").asString()).startsWith("Eres el asistente de AgroSense")
				.contains("Fecha y hora actual:", "Cultivo Café: humedad del suelo 31.5 % (rango 40–80 %).",
						"Alerta en Café: Humedad baja.");
		JsonNode messages = sent.path("messages");
		assertThat(messages).hasSize(3);
		assertThat(messages.path(0).path("role").asString()).isEqualTo("user");
		assertThat(messages.path(0).path("content").asString()).isEqualTo("¿Riego hoy?");
		assertThat(messages.path(1).path("role").asString()).isEqualTo("assistant");
		assertThat(messages.path(2).path("content").asString()).isEqualTo("¿Y mañana?");
	}

	@Test
	void whenClaudeIsOutOfAllowanceOverloadedBrokenOrSilentTheFarmsFiguresAnswer() {
		AiAssistantService assistant = assistant(true);

		for (int failure : new int[] {429, 529, 500, 401}) {
			status.set(failure);
			answer.set("{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"" + KEY + "\"}}");
			AiResponseDto response = assistant.chat(EMAIL, question("¿Qué alertas tengo?"));
			assertThat(response.source()).isEqualTo("local");
			assertThat(response.reply()).startsWith("Tienes 1 alerta sin atender:").doesNotContain(KEY);
		}
		status.set(200);
		answer.set(message("", "max_tokens"));
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");

		answer.set("{\"type\":\"message\",\"stop_reason\":\"refusal\",\"content\":[]}");
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.UNPROCESSABLE_ENTITY);

		anthropic.stop(0);
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
	}

	@Test
	void withoutLocalAnswersAnUnavailableClaudeIsA503() {
		AiAssistantService assistant = assistant(false);

		for (int failure : new int[] {429, 529}) {
			status.set(failure);
			assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.SERVICE_UNAVAILABLE);
		}
		anthropic.stop(0);
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void claudeDoesNotListenSoARecordingIsAskedInWritingAndATranscriptIsAnswered() {
		AiAssistantService assistant = assistant(true);

		assertThatThrownBy(() -> assistant.voice(EMAIL, new byte[] {1}, "audio/webm", null))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.getMessage()).contains("Escribe tu pregunta");
				});
		assertThat(paths).isEmpty();

		AiResponseDto spoken = assistant.voice(EMAIL, null, null, " ¿Riego hoy? ");
		assertThat(spoken.transcript()).isEqualTo("¿Riego hoy?");
		assertThat(spoken.source()).isEqualTo("model");
		assertThat(paths).containsExactly("/v1/messages");
	}

	@Test
	void theProviderIsChosenByPriorityOpenAiCompatibleThenClaudeThenGemini() {
		String claudeAddress = address();
		assertThat(AiAssistantService.chosenModel("chat-key", "http://127.0.0.1:9", "chat-test", "",
				KEY, claudeAddress, "claude-test", "low", 2000, 256))
				.isInstanceOf(com.agrosense.backend.ai.service.OpenAiCompatibleClient.class);
		assertThat(AiAssistantService.chosenModel(" ", "http://127.0.0.1:9", "chat-test", "",
				KEY, claudeAddress, "claude-test", "low", 2000, 256))
				.isInstanceOf(AnthropicClient.class);
		assertThat(AiAssistantService.chosenModel(" ", "http://127.0.0.1:9", "chat-test", "",
				"", claudeAddress, "claude-test", "low", 2000, 256)).isNull();

		assertThat(assistant(true).chat(EMAIL, question("¿Riego?")).source()).isEqualTo("model");
		assertThat(paths).containsExactly("/v1/messages");
	}

	@Test
	void anEmptyEffortIsLeftOutForModelsThatDoNotTakeIt() {
		new AiAssistantService(farm, JSON, Clock.systemUTC(), "", "gemini-test", "http://127.0.0.1:9", 2000, 256,
				0, true, new AnthropicClient(KEY, address(), "claude-older", " ", 2000, 256))
				.chat(EMAIL, question("Hola"));

		assertThat(JSON.readTree(bodies.get(0)).has("output_config")).isFalse();
	}

	private AiAssistantService assistant(boolean localAnswers) {
		return new AiAssistantService(farm, JSON, Clock.systemUTC(), "a-gemini-key", "gemini-test",
				"http://127.0.0.1:9", 2000, 256, 0, localAnswers,
				new AnthropicClient(KEY, address(), " claude-test ", "low", 2000, 256));
	}

	private String address() {
		return "http://127.0.0.1:" + anthropic.getAddress().getPort() + "/";
	}

	private static String message(String text, String stopReason) {
		return "{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"" + stopReason
				+ "\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
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

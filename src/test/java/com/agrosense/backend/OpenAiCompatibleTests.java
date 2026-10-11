package com.agrosense.backend;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
import com.agrosense.backend.ai.service.FarmContextService;
import com.agrosense.backend.ai.service.FarmSnapshot;
import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.agrosense.backend.ai.service.OpenAiCompatibleClient;
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

/**
 * The assistant answered by a provider that speaks the OpenAI API (Groq, OpenRouter, Mistral...), against
 * a local server that plays its part; no request leaves the machine.
 */
class OpenAiCompatibleTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String KEY = "test-provider-key";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private HttpServer provider;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> chatAnswer = new AtomicReference<>(completion("Riega el **café** hoy.", "stop"));
	private final AtomicReference<String> heard = new AtomicReference<>("{\"text\":\" ¿Cuándo riego el café? \"}");
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private final List<String> contentTypes = new CopyOnWriteArrayList<>();
	private final FarmContextService farm = mock(FarmContextService.class);

	@BeforeEach
	void startProvider() throws IOException {
		provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext("/", exchange -> {
			String path = exchange.getRequestURI().toString();
			paths.add(path);
			authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
			// ISO-8859-1 keeps every byte of a recording as one character, so the form can be searched as text.
			bodies.add(new String(exchange.getRequestBody().readAllBytes(),
					path.endsWith("/audio/transcriptions") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
			byte[] body = (path.endsWith("/audio/transcriptions") ? heard.get() : chatAnswer.get())
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		provider.start();
		when(farm.snapshot(EMAIL)).thenReturn(new FarmSnapshot(1, 2, 1,
				List.of(new CropStatus("Café", new BigDecimal("31.5000"), new BigDecimal("40.0"), new BigDecimal("80.0"),
						null, new BigDecimal("15.0"), new BigDecimal("35.0"),
						null, new BigDecimal("5.5"), new BigDecimal("7.0"))),
				List.of(new OpenAlert("Café", "Humedad baja."))));
	}

	@AfterEach
	void stopProvider() {
		provider.stop(0);
	}

	@Test
	void aQuestionGoesToTheProviderWithTheInstructionsTheFarmAndTheEarlierTurns() {
		AiRequestDto request = question("  ¿Y mañana?  ");
		request.setHistory(List.of(turn("assistant", "Hola"), turn("user", "¿Riego hoy?"),
				turn("assistant", "Sí, riega hoy.")));

		AiResponseDto response = assistant("whisper-test", 10).chat(EMAIL, request);

		assertThat(response.reply()).isEqualTo("Riega el **café** hoy.");
		assertThat(response.source()).isEqualTo("model");
		// The address given ends with a slash and with /v1: the path is appended once, cleanly.
		assertThat(paths).containsExactly("/openai/v1/chat/completions");
		assertThat(authorizations).containsExactly("Bearer " + KEY);
		JsonNode sent = JSON.readTree(bodies.get(0));
		assertThat(sent.path("model").asString()).isEqualTo("chat-test");
		assertThat(sent.path("max_tokens").asInt()).isEqualTo(256);
		JsonNode messages = sent.path("messages");
		assertThat(messages).hasSize(4);
		assertThat(messages.path(0).path("role").asString()).isEqualTo("system");
		assertThat(messages.path(0).path("content").asString()).startsWith("Eres AgroSense IA")
				.contains("Cultivo Café: humedad del suelo 31.5 % (rango 40–80 %).", "Alerta en Café: Humedad baja.");
		// The answer with nothing before it is dropped; the provider calls the other side "assistant".
		assertThat(messages.path(1).path("role").asString()).isEqualTo("user");
		assertThat(messages.path(2).path("role").asString()).isEqualTo("assistant");
		assertThat(messages.path(3).path("content").asString()).isEqualTo("¿Y mañana?");
		assertThat(bodies.get(0)).doesNotContain(KEY);
	}

	@Test
	void aRecordingIsTurnedIntoTextAndThenAnsweredLikeAWrittenQuestion() {
		byte[] audio = {1, 2, 3, 4};

		AiResponseDto response = assistant("whisper-test", 1).voice(EMAIL, audio, "audio/webm;codecs=opus", null);

		assertThat(response.transcript()).isEqualTo("¿Cuándo riego el café?");
		assertThat(response.reply()).isEqualTo("Riega el **café** hoy.");
		assertThat(response.source()).isEqualTo("model");
		// Two calls, counted as one question: a limit of one a minute did not stop the second step.
		assertThat(paths).containsExactly("/openai/v1/audio/transcriptions", "/openai/v1/chat/completions");
		assertThat(contentTypes.get(0)).startsWith("multipart/form-data");
		assertThat(bodies.get(0)).contains("name=\"file\"; filename=\"voice.webm\"", "Content-Type: audio/webm",
				"name=\"model\"", "whisper-test", "name=\"language\"", "\u0001\u0002\u0003\u0004");
		assertThat(JSON.readTree(bodies.get(1)).path("messages").path(1).path("content").asString())
				.isEqualTo("¿Cuándo riego el café?");
	}

	@Test
	void whenTheProviderFailsTheQuestionIsAnsweredFromTheFarmAndARefusalStaysARefusal() {
		AiAssistantService assistant = assistant("whisper-test", 0);

		for (int failure : new int[] {429, 500, 401}) {
			status.set(failure);
			AiResponseDto response = assistant.chat(EMAIL, question("¿Cuándo conviene regar?"));
			assertThat(response.source()).isEqualTo("local");
			assertThat(response.reply()).contains("**Café**: riega. La humedad (31.5 %)");
		}
		status.set(200);
		chatAnswer.set(completion("", "length"));
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
		chatAnswer.set(completion("", "content_filter"));
		assertStatus(() -> assistant.chat(EMAIL, question("Hola")), HttpStatus.UNPROCESSABLE_ENTITY);

		// A recording heard, and then a provider that cannot answer: the text is still answered.
		chatAnswer.set(completion("no llega", "stop"));
		AiAssistantService flaky = assistant("whisper-test", 0);
		provider.removeContext("/");
		provider.createContext("/", exchange -> {
			boolean listening = exchange.getRequestURI().toString().endsWith("/audio/transcriptions");
			exchange.getRequestBody().readAllBytes();
			byte[] body = heard.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(listening ? 200 : 503, listening ? body.length : -1);
			if (listening) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		AiResponseDto spoken = flaky.voice(EMAIL, new byte[] {9}, "audio/ogg", null);
		assertThat(spoken.transcript()).isEqualTo("¿Cuándo riego el café?");
		assertThat(spoken.source()).isEqualTo("local");
		assertThat(spoken.reply()).contains("**Café**: riega");

		provider.stop(0);
		assertThat(assistant.chat(EMAIL, question("¿Riego?")).source()).isEqualTo("local");
	}

	@Test
	void aRecordingNobodyUnderstoodOrAProviderThatCannotListenAsksForWriting() {
		heard.set("{\"text\":\"   \"}");
		assertThatThrownBy(() -> assistant("whisper-test", 0).voice(EMAIL, new byte[] {1}, "audio/webm", null))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
					assertThat(error.getMessage()).contains("escribe tu pregunta");
				});
		status.set(500);
		assertStatus(() -> assistant("whisper-test", 0).voice(EMAIL, new byte[] {1}, "audio/webm", null),
				HttpStatus.BAD_GATEWAY);

		// A provider configured without a transcription model: recordings are not sent anywhere.
		status.set(200);
		int before = paths.size();
		assertThatThrownBy(() -> assistant("  ", 0).voice(EMAIL, new byte[] {1}, "audio/webm", null))
				.isInstanceOfSatisfying(AiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.getMessage()).contains("Escribe tu pregunta");
				});
		assertThat(paths).hasSize(before);
		// What was said, sent as text, is answered as always.
		assertThat(assistant("  ", 0).voice(EMAIL, null, null, "¿Riego hoy?").transcript()).isEqualTo("¿Riego hoy?");
	}

	// ---------- helpers ----------

	/** An assistant with this provider and a Gemini key too: the provider is the one that answers. */
	private AiAssistantService assistant(String transcriptionModel, int requestsPerMinute) {
		String address = "http://127.0.0.1:" + provider.getAddress().getPort() + "/openai/v1/";
		return new AiAssistantService(farm, JSON, Clock.systemUTC(), "a-gemini-key", "gemini-test",
				"http://127.0.0.1:9", 2000, 256, requestsPerMinute, true,
				new OpenAiCompatibleClient(KEY, address, " chat-test ", transcriptionModel, 2000, 256));
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

	/** A chat completion whose only choice says {@code text} (already escaped for JSON). */
	private static String completion(String text, String finishReason) {
		return "{\"choices\":[{\"index\":0,\"finish_reason\":\"" + finishReason
				+ "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
	}
}

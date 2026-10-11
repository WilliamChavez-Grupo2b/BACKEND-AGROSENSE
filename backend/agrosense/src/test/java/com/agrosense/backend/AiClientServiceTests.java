package com.agrosense.backend;

import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.service.AiClientService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The client of the prediction service against a local server that plays its part. */
class AiClientServiceTests {

	private HttpServer service;
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicReference<String> receivedPath = new AtomicReference<>();
	private final AtomicReference<String> receivedBody = new AtomicReference<>();
	private final AtomicReference<String> answer = new AtomicReference<>(
			"{\"recommendation\":\"Riega ahora.\",\"confidence\":0.77671,\"model_used\":\"agrosense-riego-reglas-1.0\"}");
	private AiClientService client;

	@BeforeEach
	void startService() throws IOException {
		service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		service.createContext("/", exchange -> {
			calls.incrementAndGet();
			receivedPath.set(exchange.getRequestURI().toString());
			receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		service.start();
		client = new AiClientService(
				WebClient.builder().baseUrl("http://127.0.0.1:" + service.getAddress().getPort()).build(),
				"/predict/irrigation");
	}

	@AfterEach
	void stopService() {
		service.stop(0);
	}

	@Test
	void onlyWhatWasMeasuredIsSentAndTheAnswerIsRead() {
		Optional<IrrigationPrediction> prediction = client.predictIrrigation(7,
				Map.of("soil_moisture", new BigDecimal("31.5000"), "ph", new BigDecimal("6.4")));

		assertThat(receivedPath.get()).isEqualTo("/predict/irrigation");
		assertThat(receivedBody.get()).contains("\"id_crop\":7", "\"soil_moisture\":31.5", "\"ph\":6.4")
				.doesNotContain("air_temperature");
		assertThat(prediction).isPresent();
		assertThat(prediction.get().recommendation()).isEqualTo("Riega ahora.");
		assertThat(prediction.get().confidence()).isEqualByComparingTo("0.7767");
		assertThat(prediction.get().modelUsed()).isEqualTo("agrosense-riego-reglas-1.0");
	}

	@Test
	void aCropWithoutSoilMoistureReadingsIsNotPredictedFromMadeUpValues() {
		assertThat(client.predictIrrigation(7, Map.of())).isEmpty();
		assertThat(client.predictIrrigation(7, Map.of("air_temperature", new BigDecimal("24.0")))).isEmpty();
		assertThat(calls.get()).isZero();
	}

	@Test
	void anAnswerWithoutARecommendationOrWithAnImpossibleConfidenceIsNotTrusted() {
		answer.set("{\"confidence\":0.9}");
		assertThat(client.predictIrrigation(7, Map.of("soil_moisture", 30))).isEmpty();

		answer.set("{\"recommendation\":\"Riega.\",\"confidence\":7,\"model\":\"otro\"}");
		IrrigationPrediction prediction = client.predictIrrigation(7, Map.of("soil_moisture", 30)).orElseThrow();
		assertThat(prediction.confidence()).isNull();
		assertThat(prediction.modelUsed()).isEqualTo("otro");
	}
}

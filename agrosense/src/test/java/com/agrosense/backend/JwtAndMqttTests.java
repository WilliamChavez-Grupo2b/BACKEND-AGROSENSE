package com.agrosense.backend;

import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.mqtt.MqttMessageHandler;
import com.agrosense.backend.pattern.structural.adapter.MqttAdapter;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAndMqttTests {

	private static final String SECRET = "unit-test-secret-of-at-least-64-bytes-for-hs512-signatures-0123456789";
	private static final String TOPIC = "agrosense/sensors/A/readings";

	@Test
	void tokensRoundTripAndAreRejectedWhenForgedOrMalformed() {
		JwtUtil jwt = new JwtUtil(SECRET, 60);
		String token = jwt.generateToken("farmer@agrosense.test", "farmer");

		assertThat(jwt.extractEmail(token)).contains("farmer@agrosense.test");
		assertThat(jwt.extractRole(token)).contains("farmer");
		assertThat(jwt.isTokenValid(token)).isTrue();
		assertThat(jwt.isTokenValid(token + "x")).isFalse();
		assertThat(jwt.extractEmail(token + "x")).isEmpty();
		assertThat(jwt.extractEmail("")).isEmpty();
		assertThat(jwt.extractEmail("a.b.c")).isEmpty();
		// Signed with a different key: a forged token.
		assertThat(new JwtUtil("another-secret-of-at-least-64-bytes-for-hs512-signatures-0123456789", 60).extractEmail(token)).isEmpty();
		// Unsigned token with "alg":"none".
		String unsigned = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYXJtZXJAYWdyb3NlbnNlLnRlc3QifQ.";
		assertThat(jwt.extractEmail(unsigned)).isEmpty();
	}

	@Test
	void weakOrMissingSecretsStopTheApplicationFromStarting() {
		assertThatThrownBy(() -> new JwtUtil("", 60)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtUtil("too-short", 60)).isInstanceOf(IllegalStateException.class);
		// Long enough for HS256 but not for HS512.
		assertThatThrownBy(() -> new JwtUtil("a-secret-of-thirty-two-bytes-only", 60))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtUtil(SECRET, 0)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void mqttMessagesAreAdaptedAndRecorded() {
		SensorFacade facade = mock(SensorFacade.class);
		MqttMessageHandler handler = new MqttMessageHandler(JsonMapper.builder().build(), new MqttAdapter(), facade, "agrosense/sensors/+/readings");

		handler.handle("agrosense/sensors/as-001/readings",
				"{\"code\":\"as-001\",\"value\":52.3,\"unit\":\"%\",\"extra\":true}".getBytes(StandardCharsets.UTF_8));

		ArgumentCaptor<SensorReadingData> recorded = ArgumentCaptor.forClass(SensorReadingData.class);
		verify(facade).recordReading(recorded.capture());
		assertThat(recorded.getValue().getSensorCode()).isEqualTo("AS-001");
		assertThat(recorded.getValue().getValue()).isEqualByComparingTo("52.3");
		assertThat(recorded.getValue().getUnit()).isEqualTo("%");

		// A device cannot report for another sensor, nor from a topic outside the subscription.
		byte[] spoofed = "{\"code\":\"as-001\",\"value\":1}".getBytes(StandardCharsets.UTF_8);
		handler.handle("agrosense/sensors/as-002/readings", spoofed);
		handler.handle("agrosense/sensors/as-001/readings/extra", spoofed);
		handler.handle("other/sensors/as-001/readings", spoofed);
		handler.handle("agrosense/sensors//readings", spoofed);
		verify(facade, times(1)).recordReading(any());
	}

	@Test
	void badMqttMessagesAreDroppedWithoutBreakingTheListener() {
		SensorFacade facade = mock(SensorFacade.class);
		MqttMessageHandler handler = new MqttMessageHandler(JsonMapper.builder().build(), new MqttAdapter(), facade, "agrosense/sensors/+/readings");

		assertThatCode(() -> {
			handler.handle(TOPIC, null);
			handler.handle(TOPIC, new byte[0]);
			handler.handle(TOPIC, "not json".getBytes(StandardCharsets.UTF_8));
			handler.handle(TOPIC, "{\"value\":1}".getBytes(StandardCharsets.UTF_8));
			handler.handle(TOPIC, "{\"code\":\"A\"}".getBytes(StandardCharsets.UTF_8));
			handler.handle(TOPIC, ("{\"code\":\"" + "A".repeat(4000) + "\",\"value\":1}").getBytes(StandardCharsets.UTF_8));
		}).doesNotThrowAnyException();
		verify(facade, never()).recordReading(any());

		// Failures inside the application (unknown sensor, database down) are contained too.
		when(facade.recordReading(any())).thenThrow(new ResourceNotFoundException("unknown"))
				.thenThrow(new IllegalStateException("database down"));
		byte[] valid = "{\"code\":\"A\",\"value\":1}".getBytes(StandardCharsets.UTF_8);
		assertThatCode(() -> {
			handler.handle(TOPIC, valid);
			handler.handle(TOPIC, valid);
		}).doesNotThrowAnyException();
	}
}

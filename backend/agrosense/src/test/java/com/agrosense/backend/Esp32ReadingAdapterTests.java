package com.agrosense.backend;

import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class Esp32ReadingAdapterTests {

    private static final String DEVICE = "RPI-DEMO-01";

    // TODO(esp32-tcp): replace these two with a line copied from the real ESP32 once it is available; the request asked
    // for "the real example" and none was given.
    private static final String JSON_LINE = "{\"temp\": 24.5, \"hum\": 61, \"suelo\": 31.2}";
    private static final String PAIRS_LINE = "temperatura=24.5,humedad_aire=61,humedad_suelo=31.2";

    private final Esp32ReadingAdapter adapter = new Esp32ReadingAdapter();

    @Test
    void flatJsonBecomesTheWebhooksEvent() {
        DeviceEventRequest event = adapter.adapt(JSON_LINE, DEVICE).orElseThrow();

        assertThat(event.getDeviceId()).isEqualTo(DEVICE);
        assertThat(event.getType()).isEqualTo("reading");
        assertThat(event.getTs()).isNull();  // the backend stamps the arrival: the ESP32's clock is not synchronized
        assertMeasures(event.getData(), 24.5, 61, 31.2);
    }

    @Test
    void pairsWithEqualsOrColonsAndEitherSeparatorGiveTheSameEvent() {
        assertMeasures(adapter.adapt(PAIRS_LINE, DEVICE).orElseThrow().getData(), 24.5, 61, 31.2);
        assertMeasures(adapter.adapt("temp:24.5; hum:61; suelo:31.2", DEVICE).orElseThrow().getData(), 24.5, 61, 31.2);
        assertMeasures(adapter.adapt("Temperatura = 24,5 °C ; Humedad = 61 % ; Humedad-Suelo = 31,2%\r", DEVICE)
                .orElseThrow().getData(), 24.5, 61, 31.2);
        assertMeasures(adapter.adapt("{\"data\": {\"temperature\": \"24.5\", \"humidity\": 61, \"soil\": 31.2}}", DEVICE)
                .orElseThrow().getData(), 24.5, 61, 31.2);
    }

    @Test
    void unknownFieldsAreIgnoredAndMissingOnesAreNotInvented() {
        JsonNode data = adapter.adapt("id=7,rssi=-61,suelo=40,ts=12345,ph=6.5", DEVICE).orElseThrow().getData();

        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get("soil_moisture").decimalValue()).isEqualByComparingTo("40");
        assertThat(data.has("air_temperature")).isFalse();
        assertThat(data.has("ph")).isFalse();
    }

    @Test
    void aValueThatIsNotANumberIsLeftOutAndTheRestIsKept() {
        JsonNode data = adapter.adapt("temp=nan,hum=error,suelo=33", DEVICE).orElseThrow().getData();

        assertThat(data.size()).isEqualTo(1);
        assertThat(data.has("soil_moisture")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "hola", "ESP32 listo", "temp=", "=24", "temp=abc,hum=--", "{\"temp\": null}",
            "{\"temp\": [1, 2]}", "{no es json", "[24.5, 61]", "rssi=-60,uptime=1200"})
    void aLineWithoutAnyUsableMeasureIsDropped(String line) {
        assertThat(adapter.adapt(line, DEVICE)).isEqualTo(Optional.empty());
    }

    @Test
    void anOverlongLineIsDropped() {
        assertThat(adapter.adapt("suelo=30," + "x".repeat(2000), DEVICE)).isEmpty();
        assertThat(adapter.adapt(null, DEVICE)).isEmpty();
    }

    private static void assertMeasures(JsonNode data, double temperature, double humidity, double soil) {
        assertThat(data.size()).isEqualTo(3);
        assertThat(data.get("air_temperature").asDouble()).isEqualTo(temperature);
        assertThat(data.get("relative_humidity").asDouble()).isEqualTo(humidity);
        assertThat(data.get("soil_moisture").asDouble()).isEqualTo(soil);
    }
}

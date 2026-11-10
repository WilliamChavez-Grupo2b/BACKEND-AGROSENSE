package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.service.DeviceEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter: turns one line the ESP32 sends over TCP into the event POST /api/internal/events already receives, so the
 * rest of the backend never learns that this device speaks differently. A line may be flat JSON
 * ({"temp": 24.5, "hum": 61, "suelo": 31.5}) or pairs separated by commas or semicolons, written key=value or
 * key:value (temp=24.5,hum=61,suelo=31.5). A decimal comma is only possible with semicolons between the pairs.
 *
 * <p>Only the measures it can name are kept; unknown keys are ignored, and a line with no usable measure is logged and
 * dropped. Nothing is ever filled in. The line's own time, if any, is ignored too: the ESP32 runs its own network
 * without internet, so its clock is not synchronized, and the backend stamps the arrival instead.
 */
@Slf4j
@Component
public class Esp32ReadingAdapter {

    static final int MAX_LINE = 1024;

    /** The ESP32's names, already normalized (lowercase, no accents, '_' for spaces and dashes), and the contract's. */
    private static final Map<String, String> MEASURES = Map.ofEntries(
            Map.entry("temp", "air_temperature"),
            Map.entry("temperatura", "air_temperature"),
            Map.entry("temperature", "air_temperature"),
            Map.entry("temp_aire", "air_temperature"),
            Map.entry("temperatura_aire", "air_temperature"),
            Map.entry("air_temperature", "air_temperature"),
            Map.entry("hum", "relative_humidity"),
            Map.entry("humedad", "relative_humidity"),
            Map.entry("humedad_aire", "relative_humidity"),
            Map.entry("hum_aire", "relative_humidity"),
            Map.entry("humidity", "relative_humidity"),
            Map.entry("relative_humidity", "relative_humidity"),
            Map.entry("suelo", "soil_moisture"),
            Map.entry("humedad_suelo", "soil_moisture"),
            Map.entry("hum_suelo", "soil_moisture"),
            Map.entry("soil", "soil_moisture"),
            Map.entry("soil_moisture", "soil_moisture"));

    /** A number, with a decimal point or comma, and at most a unit after it: 24.5, 24,5, 61%, 24.5 °C. */
    private static final Pattern NUMBER = Pattern.compile("^([-+]?\\d+(?:[.,]\\d+)?)\\s*(?:%|°\\s*c?|c)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMA = Pattern.compile(",");
    private static final Pattern SEMICOLON = Pattern.compile(";");

    private final JsonMapper json = JsonMapper.builder().build();

    public Optional<DeviceEventRequest> adapt(String line, String deviceId) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.length() > MAX_LINE) {
            log.warn("ESP32: line of {} characters dropped, longer than {}", text.length(), MAX_LINE);
            return Optional.empty();
        }
        Map<String, String> fields = text.startsWith("{") ? jsonFields(text) : pairFields(text);
        ObjectNode data = json.createObjectNode();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String measure = MEASURES.get(normalize(field.getKey()));
            if (measure == null || data.has(measure)) {
                continue;
            }
            numberOf(field.getValue()).ifPresent(value -> data.put(measure, value));
        }
        if (data.isEmpty()) {
            log.warn("ESP32: line without any usable measure dropped: {}", abbreviate(text));
            return Optional.empty();
        }
        DeviceEventRequest request = new DeviceEventRequest();
        request.setDeviceId(deviceId);
        request.setType(DeviceEventService.READING);
        request.setData(data);
        return Optional.of(request);
    }

    private Map<String, String> jsonFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (JacksonException notJson) {
            return fields;
        }
        if (root == null || !root.isObject()) {
            return fields;
        }
        // {"data": {...}} as the webhook's own form, or the measures at the top.
        JsonNode measures = root.path("data").isObject() ? root.get("data") : root;
        for (Map.Entry<String, JsonNode> property : measures.properties()) {
            JsonNode value = property.getValue();
            if (value.isNumber() || value.isString()) {
                fields.put(property.getKey(), value.isNumber() ? value.decimalValue().toPlainString() : value.asString());
            }
        }
        return fields;
    }

    private static Map<String, String> pairFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        // With semicolons between pairs a comma can only be a decimal one (24,5); without them it separates pairs.
        for (String pair : (text.indexOf(';') >= 0 ? SEMICOLON : COMMA).split(text)) {
            int equals = pair.indexOf('=');
            int colon = pair.indexOf(':');
            int at = equals >= 0 && (colon < 0 || equals < colon) ? equals : colon;
            if (at > 0) {
                fields.putIfAbsent(pair.substring(0, at), pair.substring(at + 1));
            }
        }
        return fields;
    }

    private static Optional<BigDecimal> numberOf(String raw) {
        Matcher number = NUMBER.matcher(raw == null ? "" : raw.strip());
        if (!number.matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(number.group(1).replace(',', '.')));
    }

    static String normalize(String key) {
        String plain = Normalizer.normalize(key.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_").replaceAll("^\"|\"$", "");
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}

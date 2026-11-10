package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceEventService {

    public static final String READING = "reading";
    public static final String ALERT = "alert";

    static final int MAX_MEASUREMENTS = 16;
    static final Duration ALLOWED_CLOCK_AHEAD = Duration.ofMinutes(5);
    /** A clock that has not synchronized counts from its own boot; such a time would be stored as 1970. */
    static final Instant EARLIEST_EVENT = Instant.parse("2025-01-01T00:00:00Z");
    /** reading_value is NUMERIC(12, 4): past this it does not even fit as a reading marked impossible. */
    private static final double LARGEST_VALUE = 1e8;
    private static final double LARGEST_TS = 1e15;

    private static final String MEASUREMENTS = Arrays.stream(SensorType.values())
            .map(DeviceEventService::measurementName).collect(Collectors.joining(", "));

    private final DeviceService deviceService;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final CropRepository cropRepository;
    private final SensorFacade sensorFacade;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional
    public Map<String, Object> receive(String deviceCode, String deviceKey, String type, JsonNode ts, JsonNode data) {
        return store(deviceService.require(deviceCode, deviceKey), type, ts, data);
    }

    /** The same event from a device trusted by configuration, without a key: the ESP32 read over TCP. */
    @Transactional
    public Map<String, Object> receiveFromConfiguredDevice(String deviceCode, String type, JsonNode ts, JsonNode data) {
        return store(deviceService.requireConfigured(deviceCode), type, ts, data);
    }

    private Map<String, Object> store(Device device, String type, JsonNode ts, JsonNode data) {
        Crop crop = device.getCrop();
        LocalDateTime when = timeOf(ts);
        if (data == null || !data.isObject()) {
            throw invalid("El campo 'data' debe ser un objeto JSON.");
        }
        List<SensorReadingResponse> readings = new ArrayList<>();
        List<AlertResponse> alerts = new ArrayList<>();
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case READING -> readings(crop, when, ts != null && !ts.isNull(), data, readings, alerts);
            case ALERT -> alert(crop, data).map(AlertResponse::from).ifPresent(alerts::add);
            default -> throw invalid("El tipo de evento debe ser 'reading' o 'alert'.");
        }
        device.setLastEventAt(LocalDateTime.now(clock));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", kind);
        event.put("deviceId", device.getDeviceCode());
        event.put("cropId", crop.getIdCrop());
        event.put("cropName", crop.getName());
        event.put("readings", readings);
        event.put("alerts", alerts);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ownerEmail", crop.getEstate().getUser().getEmail());
        answer.put("event", event);
        return answer;
    }

    private void readings(Crop crop, LocalDateTime when, boolean stamped, JsonNode data,
            List<SensorReadingResponse> readings, List<AlertResponse> alerts) {
        if (data.isEmpty()) {
            throw invalid("Una lectura necesita al menos una medida en 'data', por ejemplo {\"soil_moisture\": 31.5}.");
        }
        if (data.size() > MAX_MEASUREMENTS) {
            throw invalid("Un evento admite como máximo " + MAX_MEASUREMENTS + " medidas.");
        }
        if (stamped) {
            // Two copies of one event at once, the second a retry of the first, must not both find nothing stored.
            cropRepository.findByIdForUpdate(crop.getIdCrop());
        }
        List<Sensor> active = sensorRepository.findByCropIdCropAndActiveTrue(crop.getIdCrop());
        for (Map.Entry<String, JsonNode> measurement : data.properties()) {
            String name = measurement.getKey();
            SensorType type = typeOf(name);
            JsonNode given = measurement.getValue();
            BigDecimal value = valueOf(name, given.isObject() ? given.get("value") : given);
            String sensorCode = given.isObject() && given.hasNonNull("sensor_code")
                    ? given.get("sensor_code").asString("") : null;
            Sensor sensor = sensorFor(crop, active, type, name, value, sensorCode);
            // A device stamps an event when it measures, so a retry arrives with the same time: already stored.
            if (stamped && readingRepository.existsBySensorIdSensorAndRecordedAt(sensor.getIdSensor(), when)) {
                continue;
            }
            SensorFacade.Recorded recorded = sensorFacade.record(SensorReadingData.builder()
                    .sensorCode(sensor.getSensorCode()).value(value).recordedAt(when).build());
            readings.add(SensorReadingResponse.from(recorded.reading()));
            recorded.alert().map(AlertResponse::from).ifPresent(alerts::add);
        }
    }

    private Sensor sensorFor(Crop crop, List<Sensor> active, SensorType type, String name, BigDecimal value,
            String sensorCode) {
        if (sensorCode != null) {
            String code = sensorCode.trim().toUpperCase(Locale.ROOT);
            Sensor named = active.stream().filter(sensor -> sensor.getSensorCode().equals(code)).findFirst()
                    .orElseThrow(() -> invalid("El sensor '" + code + "' no es un sensor activo del cultivo "
                            + crop.getName() + "."));
            if (named.getSensorType() != type) {
                throw invalid("El sensor '" + code + "' no mide " + name + ".");
            }
            return named;
        }
        List<Sensor> candidates = active.stream().filter(sensor -> sensor.getSensorType() == type).toList();
        if (candidates.isEmpty()) {
            throw invalid("El cultivo " + crop.getName() + " no tiene un sensor activo de " + name + ".");
        }
        if (candidates.size() > 1) {
            String codes = candidates.stream().map(Sensor::getSensorCode).sorted().collect(Collectors.joining(", "));
            throw invalid("El cultivo " + crop.getName() + " tiene " + candidates.size() + " sensores activos de "
                    + name + " (" + codes + "): indica cuál con {\"" + name + "\": {\"value\": "
                    + value.toPlainString() + ", \"sensor_code\": \"" + candidates.get(0).getSensorCode() + "\"}}.");
        }
        return candidates.get(0);
    }

    private Optional<com.agrosense.backend.models.Alert> alert(Crop crop, JsonNode data) {
        AlertType type;
        try {
            type = AlertType.valueOf(text(data, "alert_type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("El campo 'alert_type' debe ser uno de: " + Arrays.toString(AlertType.values()) + ".");
        }
        String message = text(data, "message");
        if (message.length() > 255) {
            throw invalid("El campo 'message' no puede superar los 255 caracteres.");
        }
        Severity severity = null;
        if (data.hasNonNull("severity")) {
            try {
                severity = Severity.valueOf(data.get("severity").asString("").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw invalid("El campo 'severity' debe ser uno de: " + Arrays.toString(Severity.values()) + ".");
            }
        }
        BigDecimal value = data.hasNonNull("value") ? valueOf("value", data.get("value")) : null;
        return sensorFacade.raiseAlert(crop, type, message, severity, value);
    }

    static String measurementName(SensorType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static SensorType typeOf(String name) {
        try {
            return SensorType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("Medida desconocida '" + name + "'. Usa: " + MEASUREMENTS + ".");
        }
    }

    private static BigDecimal valueOf(String name, JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw invalid("El valor de '" + name + "' debe ser un número.");
        }
        if (Math.abs(value.asDouble()) >= LARGEST_VALUE) {
            throw invalid("El valor de '" + name + "' está fuera de toda escala.");
        }
        return value.decimalValue();
    }

    private static String text(JsonNode data, String field) {
        String value = data.hasNonNull(field) ? data.get(field).asString("").trim() : "";
        if (value.isEmpty()) {
            throw invalid("Falta el campo '" + field + "' en 'data'.");
        }
        return value;
    }

    private LocalDateTime timeOf(JsonNode ts) {
        if (ts == null || ts.isNull()) {
            return LocalDateTime.now(clock);
        }
        Instant instant;
        try {
            if (ts.isNumber()) {
                double number = ts.asDouble();
                if (Math.abs(number) >= LARGEST_TS) {
                    throw invalid("El campo 'ts' debe ser una fecha ISO-8601 o un número de segundos.");
                }
                instant = number > 100_000_000_000L ? Instant.ofEpochMilli((long) number)
                        : Instant.ofEpochSecond((long) number);
            } else if (ts.isString()) {
                String text = ts.asString().trim();
                instant = text.matches(".*([zZ]|[+-]\\d\\d:?\\d\\d)$")
                        ? OffsetDateTime.parse(text).toInstant()
                        : LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
            } else {
                throw invalid("El campo 'ts' debe ser una fecha ISO-8601 o un número de segundos.");
            }
        } catch (DateTimeException badDate) {
            throw invalid("El campo 'ts' debe ser una fecha ISO-8601 o un número de segundos.");
        }
        if (instant.isAfter(clock.instant().plus(ALLOWED_CLOCK_AHEAD))) {
            throw invalid("La hora del evento ('ts') está en el futuro; revisa el reloj del dispositivo.");
        }
        if (instant.isBefore(EARLIEST_EVENT)) {
            throw invalid("La hora del evento ('ts') es anterior a AgroSense; revisa el reloj del dispositivo.");
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}

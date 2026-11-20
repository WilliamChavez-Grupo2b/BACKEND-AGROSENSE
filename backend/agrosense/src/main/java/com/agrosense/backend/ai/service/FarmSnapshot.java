package com.agrosense.backend.ai.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public record FarmSnapshot(int totalCrops, int totalSensors, int pendingAlerts, List<CropStatus> crops,
        List<OpenAlert> alerts, List<OpenAlert> attended, List<IrrigationCycle> irrigations,
        List<SensorSource> sources, Receiver receiver, String wateringRule) {

    public static final int MAX_DESCRIPTION_LENGTH = 6000;

    /** Where every kind of data is kept: a table of the database (PostgreSQL in Neon, or H2 in the local demo). */
    public static final String STORAGE = "Dónde se guarda cada dato (base PostgreSQL en Neon; en la demo local, H2 en "
            + "tools/esp32/.run/h2): lecturas en la tabla sensor_readings, sensores en sensors, dispositivos en "
            + "devices, cultivos y sus rangos en crops, fincas en estates, alertas en alerts, riegos en irrigations "
            + "y recomendaciones de la IA en ai_predictions.";

    /** How a reading of the ESP32 travels until it is stored. */
    public static final String JOURNEY = "Cómo llega una lectura: el ESP32 (firmware/esp32-tcp) mide la humedad del "
            + "suelo, la temperatura y la humedad del aire y envía un bloque de texto por segundo, por Wi-Fi (red "
            + "PLANTICAS, TCP 8080) o por el cable USB; el backend junta los bloques de cada minuto, guarda una "
            + "lectura por sensor en sensor_readings y revisa las alertas y la regla de riego.";

    private static final Map<String, String[]> SENSOR_TYPES = Map.of(
            "SOIL_MOISTURE", new String[] {"humedad del suelo", "%"},
            "AIR_TEMPERATURE", new String[] {"temperatura del aire", "°C"},
            "RELATIVE_HUMIDITY", new String[] {"humedad del aire", "%"},
            "SOIL_TEMPERATURE", new String[] {"temperatura del suelo", "°C"},
            "PH", new String[] {"pH", ""},
            "CONDUCTIVITY", new String[] {"conductividad", "mS/cm"},
            "LIGHT", new String[] {"luminosidad", "lx"},
            "RAIN_GAUGE", new String[] {"lluvia", "mm"});

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, String> STAGES = Map.of("GERMINATION", "germinación", "GROWTH", "crecimiento",
            "FLOWERING", "floración", "FRUITING", "fructificación", "RIPENING", "maduración", "HARVEST", "cosecha");
    private static final Map<String, String> SEVERITIES = Map.of("LOW", "baja", "MEDIUM", "media", "HIGH", "alta",
            "VERY_HIGH", "muy alta");
    private static final Map<String, String> ALERT_TYPES = Map.ofEntries(
            Map.entry("LOW_HUMIDITY", "humedad baja"), Map.entry("HIGH_HUMIDITY", "humedad alta"),
            Map.entry("HIGH_TEMPERATURE", "temperatura alta"), Map.entry("LOW_TEMPERATURE", "temperatura baja"),
            Map.entry("PH_OUT_OF_RANGE", "pH fuera de rango"), Map.entry("HIGH_CONDUCTIVITY", "conductividad alta"),
            Map.entry("PEST_DETECTED", "plaga detectada"), Map.entry("RECOMMENDED_WATERING", "riego recomendado"),
            Map.entry("WATER_STRESS", "estrés hídrico"),
            Map.entry("FROST_RISK", "riesgo de helada"), Map.entry("SENSOR_OFFLINE", "dispositivo sin reportar"),
            Map.entry("SENSOR_FAULT", "sensor con lecturas dudosas"),
            Map.entry("LOW_AIR_HUMIDITY", "humedad del aire baja"),
            Map.entry("HIGH_AIR_HUMIDITY", "humedad del aire alta"));
    private static final Map<String, String> IRRIGATION_TYPES = Map.of("AUTOMATIC", "automático", "MANUAL", "manual");

    public FarmSnapshot(int totalCrops, int totalSensors, int pendingAlerts, List<CropStatus> crops,
            List<OpenAlert> alerts) {
        this(totalCrops, totalSensors, pendingAlerts, crops, alerts, List.of(), List.of());
    }

    public FarmSnapshot(int totalCrops, int totalSensors, int pendingAlerts, List<CropStatus> crops,
            List<OpenAlert> alerts, List<OpenAlert> attended, List<IrrigationCycle> irrigations) {
        this(totalCrops, totalSensors, pendingAlerts, crops, alerts, attended, irrigations, List.of(), null, null);
    }

    public FarmSnapshot {
        attended = attended == null ? List.of() : attended;
        irrigations = irrigations == null ? List.of() : irrigations;
        sources = sources == null ? List.of() : sources;
    }

    /**
     * One active sensor: what it measures, on which crop and where, the device that sends it (null when its readings
     * were loaded into the database without one) and its newest reading, if any.
     */
    public record SensorSource(String cropName, String type, String location, String device, BigDecimal value,
            LocalDateTime recordedAt) {

        public String what() {
            String[] known = SENSOR_TYPES.get(type);
            return known == null ? (type == null ? "sensor" : type.toLowerCase()) : known[0];
        }

        public String unit() {
            String[] known = SENSOR_TYPES.get(type);
            return known == null ? "" : known[1];
        }

        /** «humedad del aire de Café (Lote norte, dispositivo ESP32-PLANTICAS): 76.5 % el 2026-11-19 20:28». */
        public String describe() {
            StringBuilder where = new StringBuilder();
            detail(where, "", location);
            detail(where, "dispositivo ", device == null ? "ninguno, lecturas cargadas en la base" : device);
            StringBuilder text = new StringBuilder(what()).append(" de ").append(cropName)
                    .append(" (").append(where).append("): ");
            if (value == null) {
                return text.append("todavía sin lecturas").toString();
            }
            text.append(number(value)).append(unit().isEmpty() ? "" : " " + unit());
            return recordedAt == null ? text.toString() : text.append(" el ").append(WHEN.format(recordedAt)).toString();
        }
    }

    /** The ESP32 receiver of the backend: its device, its state (State) and how it averages a minute (Strategy). */
    public record Receiver(boolean enabled, String deviceId, String linkLabel, String aggregation,
            int aggregateSeconds, LocalDateTime lastStoredAt) {

        public String describe() {
            if (!enabled) {
                return "Receptor del ESP32 apagado: las lecturas llegan como eventos HTTP o ya están en la base.";
            }
            StringBuilder text = new StringBuilder("Receptor del ESP32 ").append(deviceId == null ? "" : deviceId)
                    .append(": ").append(linkLabel == null ? "estado desconocido" : linkLabel).append("; guarda ")
                    .append("median".equals(aggregation) ? "la mediana" : "el promedio")
                    .append(" de los bloques de cada ").append(aggregateSeconds).append(" s");
            if (lastStoredAt != null) {
                text.append("; última lectura guardada el ").append(WHEN.format(lastStoredAt));
            }
            return text.append('.').toString();
        }
    }

    public record CropStatus(String name,
            BigDecimal humidity, BigDecimal humidityMin, BigDecimal humidityMax,
            BigDecimal temperature, BigDecimal tempMin, BigDecimal tempMax,
            BigDecimal ph, BigDecimal phMin, BigDecimal phMax,
            String variety, String stage, LocalDate sowingDate, String estate, String location) {

        public CropStatus(String name,
                BigDecimal humidity, BigDecimal humidityMin, BigDecimal humidityMax,
                BigDecimal temperature, BigDecimal tempMin, BigDecimal tempMax,
                BigDecimal ph, BigDecimal phMin, BigDecimal phMax) {
            this(name, humidity, humidityMin, humidityMax, temperature, tempMin, tempMax, ph, phMin, phMax,
                    null, null, null, null, null);
        }

        public boolean hasReadings() {
            return humidity != null || temperature != null || ph != null;
        }
    }

    public record OpenAlert(String cropName, String message, String type, String severity,
            BigDecimal detectedValue, LocalDateTime createdAt) {

        public OpenAlert(String cropName, String message) {
            this(cropName, message, null, null, null, null);
        }
    }

    public record IrrigationCycle(String cropName, LocalDateTime startedAt, Integer durationMin,
            BigDecimal waterLiters, String type, String reason) {
    }

    public String describe() {
        StringBuilder text = new StringBuilder("Datos de la finca del usuario: ")
                .append(totalCrops).append(" cultivos, ")
                .append(totalSensors).append(" sensores, ")
                .append(pendingAlerts).append(" alertas sin atender.");
        for (CropStatus crop : crops) {
            text.append("\nCultivo ").append(crop.name()).append(profile(crop)).append(": ");
            if (!crop.hasReadings()) {
                text.append("sin lecturas.");
                continue;
            }
            StringBuilder readings = new StringBuilder();
            reading(readings, "humedad del suelo ", crop.humidity(), " %", crop.humidityMin(), crop.humidityMax());
            reading(readings, "temperatura ", crop.temperature(), " °C", crop.tempMin(), crop.tempMax());
            reading(readings, "pH ", crop.ph(), "", crop.phMin(), crop.phMax());
            text.append(readings).append('.');
        }
        if (totalCrops > crops.size()) {
            text.append("\nHay ").append(totalCrops - crops.size()).append(" cultivos más que no se detallan.");
        }
        if (receiver != null) {
            text.append('\n').append(receiver.describe());
        }
        if (wateringRule != null && !wateringRule.isBlank()) {
            text.append("\nRegla de riego activa: «").append(wateringRule.trim())
                    .append("»; cuando se cumple, el cultivo recibe la alerta de riego recomendado.");
        }
        if (!sources.isEmpty()) {
            text.append("\nDe dónde sale cada dato (sensor, cultivo, lugar, dispositivo y última lectura):");
            for (SensorSource source : sources) {
                text.append("\n- ").append(source.describe()).append('.');
            }
        }
        if (receiver != null || !sources.isEmpty()) {
            text.append('\n').append(JOURNEY).append('\n').append(STORAGE);
        }
        for (OpenAlert alert : alerts) {
            text.append("\nAlerta en ").append(alert.cropName()).append(details(alert)).append(": ")
                    .append(sentence(alert.message()));
        }
        for (OpenAlert alert : attended) {
            text.append("\nAlerta atendida en ").append(alert.cropName()).append(details(alert)).append(": ")
                    .append(sentence(alert.message()));
        }
        for (IrrigationCycle cycle : irrigations) {
            String type = label(IRRIGATION_TYPES, cycle.type(), null);
            text.append("\nRiego ").append(type == null ? "" : type + " ").append("en ").append(cycle.cropName());
            if (cycle.startedAt() != null) {
                text.append(" el ").append(WHEN.format(cycle.startedAt()));
            }
            if (cycle.durationMin() != null) {
                text.append(", ").append(cycle.durationMin()).append(" min");
            }
            if (cycle.waterLiters() != null) {
                text.append(", ").append(number(cycle.waterLiters())).append(" L");
            }
            if (cycle.reason() != null && !cycle.reason().isBlank()) {
                text.append(", motivo: ").append(cycle.reason().trim());
            }
            text.append('.');
        }
        return cut(text.toString());
    }

    private static String profile(CropStatus crop) {
        StringBuilder details = new StringBuilder();
        detail(details, "variedad ", crop.variety());
        detail(details, "etapa ", label(STAGES, crop.stage(), crop.stage()));
        detail(details, "sembrado ", crop.sowingDate() == null ? null : crop.sowingDate().toString());
        String place = crop.location() == null || crop.location().isBlank() ? crop.estate()
                : crop.estate() == null || crop.estate().isBlank() ? crop.location()
                : crop.estate() + ", " + crop.location();
        detail(details, "finca ", place);
        return details.isEmpty() ? "" : " (" + details + ")";
    }

    private static String details(OpenAlert alert) {
        StringBuilder details = new StringBuilder();
        detail(details, "", label(ALERT_TYPES, alert.type(), alert.type()));
        detail(details, "severidad ", label(SEVERITIES, alert.severity(), alert.severity()));
        detail(details, "valor ", alert.detectedValue() == null ? null : number(alert.detectedValue()));
        detail(details, "", alert.createdAt() == null ? null : WHEN.format(alert.createdAt()));
        return details.isEmpty() ? "" : " (" + details + ")";
    }

    private static void detail(StringBuilder details, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!details.isEmpty()) {
            details.append(", ");
        }
        details.append(label).append(value.trim());
    }

    private static String label(Map<String, String> labels, String name, String otherwise) {
        return name == null ? otherwise : labels.getOrDefault(name, otherwise);
    }

    private static String sentence(String message) {
        String text = message == null ? "" : message.trim();
        return text.endsWith(".") ? text : text + ".";
    }

    private static String cut(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        String note = "\n(Hay más datos de la finca que no caben aquí.)";
        int end = text.lastIndexOf('\n', MAX_DESCRIPTION_LENGTH - note.length());
        return text.substring(0, Math.max(end, 0)) + note;
    }

    private static void reading(StringBuilder readings, String label, BigDecimal value, String unit,
            BigDecimal minimum, BigDecimal maximum) {
        if (value == null) {
            return;
        }
        if (!readings.isEmpty()) {
            readings.append(", ");
        }
        readings.append(label).append(number(value)).append(unit);
        if (minimum != null && maximum != null) {
            readings.append(" (rango ").append(number(minimum)).append('–').append(number(maximum)).append(unit)
                    .append(')');
        }
    }

    static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

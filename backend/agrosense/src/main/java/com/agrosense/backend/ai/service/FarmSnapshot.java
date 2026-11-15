package com.agrosense.backend.ai.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public record FarmSnapshot(int totalCrops, int totalSensors, int pendingAlerts, List<CropStatus> crops,
        List<OpenAlert> alerts, List<OpenAlert> attended, List<IrrigationCycle> irrigations) {

    public static final int MAX_DESCRIPTION_LENGTH = 4000;

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

    public FarmSnapshot {
        attended = attended == null ? List.of() : attended;
        irrigations = irrigations == null ? List.of() : irrigations;
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

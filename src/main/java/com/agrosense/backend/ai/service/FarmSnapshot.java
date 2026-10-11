package com.agrosense.backend.ai.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the assistant knows about the caller's farm when a question arrives: the totals, the latest
 * readings of each crop next to that crop's own limits, and the alerts still open. Both the model and
 * the answers given without it are built from this, so they never disagree about the figures.
 *
 * @param crops  at most {@link FarmContextService#MAX_CROPS} of them; {@code totalCrops} counts them all
 * @param alerts at most {@link FarmContextService#MAX_ALERTS} of them; {@code pendingAlerts} counts them all
 */
public record FarmSnapshot(int totalCrops, int totalSensors, int pendingAlerts, List<CropStatus> crops,
        List<OpenAlert> alerts) {

    /** A crop with its latest readings; a measurement nobody took is {@code null}, never a guess. */
    public record CropStatus(String name,
            BigDecimal humidity, BigDecimal humidityMin, BigDecimal humidityMax,
            BigDecimal temperature, BigDecimal tempMin, BigDecimal tempMax,
            BigDecimal ph, BigDecimal phMin, BigDecimal phMax) {

        public boolean hasReadings() {
            return humidity != null || temperature != null || ph != null;
        }
    }

    public record OpenAlert(String cropName, String message) {
    }

    /** The farm in a few lines of text, sent to the model with every question. */
    public String describe() {
        StringBuilder text = new StringBuilder("Datos de la finca del usuario: ")
                .append(totalCrops).append(" cultivos, ")
                .append(totalSensors).append(" sensores, ")
                .append(pendingAlerts).append(" alertas sin atender.");
        for (CropStatus crop : crops) {
            text.append("\nCultivo ").append(crop.name()).append(": ");
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
            String message = alert.message() == null ? "" : alert.message().trim();
            text.append("\nAlerta en ").append(alert.cropName()).append(": ").append(message);
            // Stored messages usually end their own sentence.
            if (!message.endsWith(".")) {
                text.append('.');
            }
        }
        return text.toString();
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

    /** Readings are stored with four decimals; 64.7400 is written as 64.74. */
    static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

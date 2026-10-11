package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.agrosense.backend.ai.service.FarmSnapshot.number;

/**
 * Answers a question from the farm's figures alone, for when the language model cannot be reached. It
 * recognises what the question is about by its words (irrigation, alerts, temperature, pH, sensors, the
 * farm in general) and states what the readings say against each crop's own limits. It does not reason
 * and does not pretend to: what it cannot answer, it says, along with what it can.
 */
final class LocalAdvisor {

    /** Air temperature from which watering in the middle of the day loses much of the water. */
    private static final BigDecimal HOT_C = new BigDecimal("30");

    private static final Pattern ALERTS = words("alert\\w*|aviso\\w*|problema\\w*|plaga\\w*|riesgo\\w*");
    private static final Pattern IRRIGATION = words(
            "rieg\\w*|regar\\w*|regando|regue|agua|humedad|humed[oa]s?|sec[oa]s?|sequia");
    private static final Pattern PH = words("ph|acidez|acid[oa]s?|alcalin\\w*");
    private static final Pattern TEMPERATURE = words("temperatura\\w*|calor|frio|helada\\w*|clima");
    private static final Pattern SENSORS = words("sensor\\w*|lectura\\w*|medicion\\w*");
    private static final Pattern OVERVIEW = words(
            "como|estado|resumen|cultivo\\w*|finca\\w*|parcela\\w*|hoy|va|van|todo");
    private static final Pattern GREETING = words("hola|buenas|buenos|gracias|ayuda\\w*|puedes|sabes");

    private static final String TOPICS = "Puedo decirte, con las lecturas de tus sensores, si conviene **regar**, "
            + "qué **alertas** tienes sin atender, y cómo van la **temperatura**, el **pH** y el estado de "
            + "cada cultivo.";

    private LocalAdvisor() {
    }

    /** @param farm {@code null} when the farm's data could not be read */
    static String answer(String question, FarmSnapshot farm) {
        if (farm == null) {
            return "Ahora no puedo leer los datos de tu finca. Inténtalo de nuevo en un momento.";
        }
        if (farm.crops().isEmpty()) {
            return "Todavía no tienes cultivos registrados. Crea una parcela con su cultivo y un sensor, y podré "
                    + "decirte cómo va.";
        }
        String text = normalise(question);
        List<CropStatus> named = farm.crops().stream()
                .filter(crop -> words(Pattern.quote(normalise(crop.name())) + "s?").matcher(text).find())
                .toList();
        List<CropStatus> crops = named.isEmpty() ? farm.crops() : named;

        if (ALERTS.matcher(text).find()) {
            return alerts(farm);
        }
        if (IRRIGATION.matcher(text).find()) {
            return irrigation(crops);
        }
        if (PH.matcher(text).find()) {
            return ph(crops);
        }
        if (TEMPERATURE.matcher(text).find()) {
            return temperature(crops);
        }
        if (SENSORS.matcher(text).find()) {
            return sensors(farm);
        }
        if (!named.isEmpty() || OVERVIEW.matcher(text).find()) {
            return overview(farm, crops, !named.isEmpty());
        }
        if (GREETING.matcher(text).find()) {
            return "Hola. " + TOPICS;
        }
        return "Ahora mismo solo puedo responder con los datos de tu finca. " + TOPICS;
    }

    private static String irrigation(List<CropStatus> crops) {
        StringBuilder answer = new StringBuilder("Según las últimas lecturas de humedad del suelo:\n");
        for (CropStatus crop : crops) {
            answer.append("\n- **").append(crop.name()).append("**: ");
            BigDecimal humidity = crop.humidity();
            if (humidity == null) {
                answer.append("no tiene lecturas de humedad; no puedo decirte si regar.");
            } else if (below(humidity, crop.humidityMin())) {
                answer.append("riega. La humedad (").append(number(humidity)).append(" %) está por debajo de su mínimo (")
                        .append(number(crop.humidityMin())).append(" %).");
                if (crop.temperature() != null && crop.temperature().compareTo(HOT_C) >= 0) {
                    answer.append(" Con ").append(number(crop.temperature()))
                            .append(" °C, hazlo al amanecer o al atardecer.");
                }
            } else if (above(humidity, crop.humidityMax())) {
                answer.append("no riegues. La humedad (").append(number(humidity)).append(" %) supera su máximo (")
                        .append(number(crop.humidityMax())).append(" %); revisa el drenaje.");
            } else {
                answer.append("no hace falta regar. La humedad (").append(number(humidity)).append(" %) está en su rango")
                        .append(range(crop.humidityMin(), crop.humidityMax(), " %")).append('.');
            }
        }
        return answer.toString();
    }

    private static String temperature(List<CropStatus> crops) {
        StringBuilder answer = new StringBuilder("Temperatura del aire según las últimas lecturas:\n");
        for (CropStatus crop : crops) {
            answer.append("\n- **").append(crop.name()).append("**: ");
            answer.append(judged(crop.temperature(), " °C", crop.tempMin(), crop.tempMax(),
                    "sin lecturas de temperatura."));
        }
        return answer.toString();
    }

    private static String ph(List<CropStatus> crops) {
        StringBuilder answer = new StringBuilder("pH del suelo según las últimas lecturas:\n");
        for (CropStatus crop : crops) {
            answer.append("\n- **").append(crop.name()).append("**: ");
            answer.append(judged(crop.ph(), "", crop.phMin(), crop.phMax(), "sin lecturas de pH."));
        }
        return answer.toString();
    }

    private static String alerts(FarmSnapshot farm) {
        if (farm.pendingAlerts() == 0 || farm.alerts().isEmpty()) {
            return "No tienes alertas sin atender.";
        }
        StringBuilder answer = new StringBuilder(farm.pendingAlerts() == 1
                ? "Tienes 1 alerta sin atender:\n"
                : "Tienes " + farm.pendingAlerts() + " alertas sin atender"
                        + (farm.pendingAlerts() > farm.alerts().size() ? "; las más recientes:\n" : ":\n"));
        for (OpenAlert alert : farm.alerts()) {
            answer.append("\n- **").append(alert.cropName()).append("**: ")
                    .append(alert.message() == null ? "" : alert.message().trim());
        }
        return answer.append("\n\nRevísalas en la página Alertas y márcalas como atendidas cuando las resuelvas.")
                .toString();
    }

    private static String sensors(FarmSnapshot farm) {
        StringBuilder answer = new StringBuilder("Tienes ").append(farm.totalSensors())
                .append(farm.totalSensors() == 1 ? " sensor activo en " : " sensores activos en ")
                .append(farm.totalCrops()).append(farm.totalCrops() == 1 ? " cultivo." : " cultivos.");
        String silent = farm.crops().stream().filter(crop -> !crop.hasReadings()).map(CropStatus::name)
                .collect(Collectors.joining(", "));
        if (!silent.isEmpty()) {
            answer.append(" Sin lecturas todavía: ").append(silent).append('.');
        }
        return answer.toString();
    }

    private static String overview(FarmSnapshot farm, List<CropStatus> crops, boolean onlyNamed) {
        StringBuilder answer = new StringBuilder();
        if (!onlyNamed) {
            answer.append("Tu finca tiene ").append(farm.totalCrops())
                    .append(farm.totalCrops() == 1 ? " cultivo, " : " cultivos, ")
                    .append(farm.totalSensors()).append(farm.totalSensors() == 1 ? " sensor y " : " sensores y ")
                    .append(farm.pendingAlerts())
                    .append(farm.pendingAlerts() == 1 ? " alerta sin atender.\n" : " alertas sin atender.\n");
        }
        for (CropStatus crop : crops) {
            answer.append("\n- **").append(crop.name()).append("**: ");
            if (!crop.hasReadings()) {
                answer.append("sin lecturas.");
                continue;
            }
            StringBuilder line = new StringBuilder();
            figure(line, "humedad ", crop.humidity(), " %", crop.humidityMin(), crop.humidityMax());
            figure(line, "temperatura ", crop.temperature(), " °C", crop.tempMin(), crop.tempMax());
            figure(line, "pH ", crop.ph(), "", crop.phMin(), crop.phMax());
            answer.append(line).append('.');
        }
        if (!onlyNamed && farm.totalCrops() > farm.crops().size()) {
            answer.append("\n\nHay ").append(farm.totalCrops() - farm.crops().size())
                    .append(" cultivos más que no detallo aquí.");
        }
        return answer.toString().trim();
    }

    /** "64.74 %" followed by a note when it is outside the crop's range. */
    private static void figure(StringBuilder line, String label, BigDecimal value, String unit,
            BigDecimal minimum, BigDecimal maximum) {
        if (value == null) {
            return;
        }
        if (!line.isEmpty()) {
            line.append(", ");
        }
        line.append(label).append(number(value)).append(unit);
        if (below(value, minimum)) {
            line.append(" (bajo: mínimo ").append(number(minimum)).append(unit).append(')');
        } else if (above(value, maximum)) {
            line.append(" (alto: máximo ").append(number(maximum)).append(unit).append(')');
        }
    }

    private static String judged(BigDecimal value, String unit, BigDecimal minimum, BigDecimal maximum,
            String missing) {
        if (value == null) {
            return missing;
        }
        String measured = number(value) + unit;
        if (below(value, minimum)) {
            return measured + ", por debajo de su mínimo (" + number(minimum) + unit + ").";
        }
        if (above(value, maximum)) {
            return measured + ", por encima de su máximo (" + number(maximum) + unit + ").";
        }
        return measured + ", en su rango" + range(minimum, maximum, unit) + ".";
    }

    private static String range(BigDecimal minimum, BigDecimal maximum, String unit) {
        return minimum == null || maximum == null ? "" : " (" + number(minimum) + "–" + number(maximum) + unit + ")";
    }

    private static boolean below(BigDecimal value, BigDecimal minimum) {
        return minimum != null && value.compareTo(minimum) < 0;
    }

    private static boolean above(BigDecimal value, BigDecimal maximum) {
        return maximum != null && value.compareTo(maximum) > 0;
    }

    /** Lower case and without accents, so "¿Cuándo riego el CAFÉ?" reads "¿cuando riego el cafe?". */
    private static String normalise(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}

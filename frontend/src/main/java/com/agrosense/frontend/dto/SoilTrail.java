package com.agrosense.frontend.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The ESP32's soil moisture, newest first, for «Monitoreo»: each reading with how much it went down (what the soil
 * consumed) or up since the reading before it, and a summary of the consumption per hour. A pause longer than
 * {@link #GAP_MINUTES} (the board unplugged, the demo stopped) is not consumption: the reading after it says so and the
 * summary only covers the readings since the pause.
 */
public record SoilTrail(String deviceCode, String cropName, BigDecimal min, BigDecimal max, List<Step> steps,
        String summary) {

    /** The ESP32 stores one reading a minute: more than this between two of them is a pause, not consumption. */
    public static final long GAP_MINUTES = 5;

    /**
     * One reading. {@code change} is null for the oldest one in the list: there is nothing before it to compare.
     * {@code gapMinutes} is how long the board was silent before it, or 0 when it came on time.
     */
    public record Step(LocalDateTime at, BigDecimal value, BigDecimal change, boolean inRange, long gapMinutes) {

        public String valueText() {
            return decimal(value, 1) + " %";
        }

        /** «bajó 0,32 %», «subió 0,50 %», «sin cambio», «tras 2 h sin datos» or «primera de la lista». */
        public String changeText() {
            if (change == null) {
                return "primera de la lista";
            }
            if (gapMinutes > 0) {
                return "tras " + span(gapMinutes) + " sin datos";
            }
            int sign = change.signum();
            if (sign == 0) {
                return "sin cambio";
            }
            return (sign < 0 ? "bajó " : "subió ") + decimal(change.abs(), 2) + " %";
        }

        /** The status-pill tone: consumed water is a warning, a rise (rain or irrigation) information, a pause neutral. */
        public String tone() {
            if (change == null || change.signum() == 0 || gapMinutes > 0) {
                return "neutral";
            }
            return change.signum() < 0 ? "warning" : "info";
        }

        public String icon() {
            if (gapMinutes > 0) {
                return "bi-clock-history";
            }
            if (change == null || change.signum() == 0) {
                return "bi-dash";
            }
            return change.signum() < 0 ? "bi-arrow-down" : "bi-arrow-up";
        }
    }

    /** A reading as stored: when and how much. */
    public record Reading(LocalDateTime at, BigDecimal value) {
    }

    public static SoilTrail of(String deviceCode, String cropName, BigDecimal min, BigDecimal max,
            List<Reading> newestFirst) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < newestFirst.size(); i++) {
            Reading reading = newestFirst.get(i);
            BigDecimal change = i + 1 < newestFirst.size()
                    ? reading.value().subtract(newestFirst.get(i + 1).value()).setScale(2, RoundingMode.HALF_UP)
                    : null;
            boolean inRange = (min == null || reading.value().compareTo(min) >= 0)
                    && (max == null || reading.value().compareTo(max) <= 0);
            long gap = i + 1 < newestFirst.size() ? gap(newestFirst.get(i + 1), reading) : 0;
            steps.add(new Step(reading.at(), reading.value(), change, inRange, gap));
        }
        return new SoilTrail(deviceCode, cropName, min, max, List.copyOf(steps), summary(newestFirst));
    }

    /** How long the board was silent between two readings, or 0 when the later one came on time. */
    private static long gap(Reading earlier, Reading later) {
        long minutes = Duration.between(earlier.at(), later.at()).toMinutes();
        return minutes > GAP_MINUTES ? minutes : 0;
    }

    /**
     * The consumption over the readings since the last pause: «En 19 min la humedad del suelo bajó 1,20 %: consume
     * 3,79 % por hora.» Right after a pause, «Volvió a medir a las 14:26, después de 2 h sin datos.» Null with fewer
     * than two readings.
     */
    static String summary(List<Reading> newestFirst) {
        if (newestFirst.size() < 2) {
            return null;
        }
        int run = 1;
        while (run < newestFirst.size() && gap(newestFirst.get(run), newestFirst.get(run - 1)) == 0) {
            run++;
        }
        if (run == 1) {
            Reading newest = newestFirst.get(0);
            return "Volvió a medir a las " + TIME.format(newest.at()) + ", después de "
                    + span(gap(newestFirst.get(1), newest)) + " sin datos.";
        }
        return consumption(newestFirst.subList(0, run));
    }

    private static String consumption(List<Reading> newestFirst) {
        Reading newest = newestFirst.get(0);
        Reading oldest = newestFirst.get(newestFirst.size() - 1);
        long minutes = Duration.between(oldest.at(), newest.at()).toMinutes();
        BigDecimal drop = oldest.value().subtract(newest.value());
        String span = "En " + span(minutes) + " la humedad del suelo ";
        if (drop.signum() == 0) {
            return span + "no cambió.";
        }
        String moved = (drop.signum() > 0 ? "bajó " : "subió ") + decimal(drop.abs(), 2) + " %";
        if (minutes <= 0 || drop.signum() < 0) {
            return span + moved + ".";
        }
        BigDecimal perHour = drop.multiply(BigDecimal.valueOf(60)).divide(BigDecimal.valueOf(minutes), 2,
                RoundingMode.HALF_UP);
        return span + moved + ": consume " + decimal(perHour, 2) + " % por hora.";
    }

    public boolean hasSteps() {
        return !steps.isEmpty();
    }

    /** The middle of the crop's range, where the bar shows best; 50 without a range. */
    public BigDecimal optimum() {
        return min == null || max == null ? BigDecimal.valueOf(50)
                : min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    public String rangeText() {
        return min == null || max == null ? null : decimal(min, 0) + " – " + decimal(max, 0) + " %";
    }

    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private static String span(long minutes) {
        return minutes >= 120 ? minutes / 60 + " h" : minutes + " min";
    }

    private static String decimal(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}

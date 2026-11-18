package com.agrosense.backend.pattern.behavioral.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.Locale;

/**
 * Strategy: how the ESP32's blocks of one window become one reading. Esp32ReadingAggregator does not know which one
 * it holds; ESP32_AGGREGATE_STRATEGY chooses it: {@code mean} (the default) or {@code median}, which ignores the odd
 * spike of a DHT11.
 */
public interface AggregationStrategy {

    /** The combined value, rounded to two decimals, or null when the field was never measured in the window. */
    Double combine(Iterator<Double> values);

    String name();

    static AggregationStrategy named(String name) {
        return switch (name == null ? "" : name.strip().toLowerCase(Locale.ROOT)) {
            case "", "mean", "promedio" -> new MeanAggregation();
            case "median", "mediana" -> new MedianAggregation();
            default -> throw new IllegalStateException(
                    "ESP32_AGGREGATE_STRATEGY must be mean or median, not '" + name + "'");
        };
    }

    static Double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

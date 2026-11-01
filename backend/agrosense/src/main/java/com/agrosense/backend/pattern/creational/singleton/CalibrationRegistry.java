package com.agrosense.backend.pattern.creational.singleton;

import com.agrosense.backend.domain.enums.SensorType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One correction per sensor type for the whole application; the enum guarantees a single instance. */
public enum CalibrationRegistry {

    INSTANCE;

    public record Calibration(BigDecimal offset, BigDecimal gain) {

        public static final Calibration NONE = new Calibration(BigDecimal.ZERO, BigDecimal.ONE);

        public BigDecimal apply(BigDecimal value) {
            return value.multiply(gain).add(offset);
        }
    }

    /**
     * Every type starts neutral on purpose. A correction is a measurement of one physical sensor against a reference:
     * a figure invented here would silently change every reading in production. SENSOR_CALIBRATION sets the real ones.
     */
    private static Map<SensorType, Calibration> defaults() {
        return Arrays.stream(SensorType.values())
                .collect(Collectors.toUnmodifiableMap(Function.identity(), type -> Calibration.NONE));
    }

    private final Map<SensorType, Calibration> calibrations = new ConcurrentHashMap<>(defaults());

    public Calibration of(SensorType type) {
        return calibrations.get(type);
    }

    public void register(SensorType type, Calibration calibration) {
        if (calibration.gain().signum() <= 0) {
            throw new IllegalArgumentException("The gain of a calibration must be positive");
        }
        calibrations.put(type, calibration);
    }

    public void clear() {
        calibrations.putAll(defaults());
    }

    /** Reads "PH=0.1:1.02,SOIL_MOISTURE=-1.5:1", that is, TYPE=offset:gain. A bad entry stops the start-up. */
    public void load(String settings) {
        clear();
        if (settings == null || settings.isBlank()) {
            return;
        }
        for (String entry : settings.split(",")) {
            String[] typeAndValues = entry.trim().split("=", 2);
            String[] values = typeAndValues.length == 2 ? typeAndValues[1].split(":", 2) : new String[0];
            if (values.length != 2) {
                throw new IllegalArgumentException("SENSOR_CALIBRATION entries look like PH=0.1:1.02, not: " + entry);
            }
            register(SensorType.valueOf(typeAndValues[0].trim().toUpperCase(Locale.ROOT)),
                    new Calibration(new BigDecimal(values[0].trim()), new BigDecimal(values[1].trim())));
        }
    }
}

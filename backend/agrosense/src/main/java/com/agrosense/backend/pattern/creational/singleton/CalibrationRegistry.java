package com.agrosense.backend.pattern.creational.singleton;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.behavioral.memento.CalibrationCheckpoint;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One correction per sensor type for the whole application; the enum guarantees a single instance.
 *
 * <p>Also the originator of a Memento: {@link #save()} gives a {@link Snapshot} only this registry can read, and
 * {@link #restore} puts it back. {@link #load} runs inside a CalibrationCheckpoint, so a setting with a bad entry
 * leaves the previous calibration whole instead of half replaced.
 */
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

    /** Memento: the calibrations at one moment. Opaque: only CalibrationRegistry reads what it holds. */
    public static final class Snapshot {

        private final Map<SensorType, Calibration> state;

        private Snapshot(Map<SensorType, Calibration> state) {
            this.state = state;
        }
    }

    public Snapshot save() {
        return new Snapshot(new EnumMap<>(calibrations));
    }

    public void restore(Snapshot snapshot) {
        calibrations.putAll(snapshot.state);
    }

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
        CalibrationCheckpoint.guard(this, () -> replaceWith(settings));
    }

    private void replaceWith(String settings) {
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

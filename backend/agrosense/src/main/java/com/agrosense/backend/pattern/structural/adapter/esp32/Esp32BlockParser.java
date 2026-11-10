package com.agrosense.backend.pattern.structural.adapter.esp32;

import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text blocks of the ESP32 (firmware/esp32-tcp) into readings, one line at a time:
 *
 * <pre>
 * ===== PLANTICAS =====
 * Temperatura exterior: 24.50 C
 * Humedad relativa: 60.00 %
 * Humedad del suelo: 31.00 %
 * (blank line)
 * </pre>
 *
 * A block starts at the header and ends at the blank line or at the next header. Labels are matched without case or
 * accents. "nan", "inf", an empty value or a value outside its physical range leaves that field out; nothing is ever
 * filled in. A block is dropped only when no field in it is usable. Not thread-safe: one connection feeds it.
 */
@Slf4j
public class Esp32BlockParser {

    public static final int MAX_LINE = 256;

    static final double MIN_TEMPERATURE = -20;
    static final double MAX_TEMPERATURE = 80;

    private static final Pattern HEADER = Pattern.compile("^=+\\s*planticas\\s*=+$");
    /** The number before the unit: 24.50, -3,5 or +12, then C, °C or %. */
    private static final Pattern NUMBER = Pattern.compile("^([-+]?\\d+(?:[.,]\\d+)?)\\s*(?:°\\s*c|c|%)?$");

    private boolean inBlock;
    private Double airTemperature;
    private Double relativeHumidity;
    private Double soilMoisture;
    private long discardedBlocks;

    /** A reading when this line closed a block that had at least one usable field. */
    public Optional<Esp32Reading> accept(String line) {
        if (line == null) {
            return Optional.empty();
        }
        if (line.length() > MAX_LINE) {
            log.warn("ESP32: line of {} characters skipped, longer than {}", line.length(), MAX_LINE);
            return Optional.empty();
        }
        String text = normalize(line);
        if (HEADER.matcher(text).matches()) {
            Optional<Esp32Reading> previous = finish();
            inBlock = true;
            return previous;
        }
        if (!inBlock) {
            return Optional.empty();
        }
        if (text.isEmpty()) {
            return finish();
        }
        int colon = text.indexOf(':');
        if (colon > 0) {
            read(text.substring(0, colon).strip(), text.substring(colon + 1).strip());
        }
        return Optional.empty();
    }

    /** Ends the block in progress, for a connection that dropped in the middle of one. */
    public Optional<Esp32Reading> finish() {
        if (!inBlock) {
            return Optional.empty();
        }
        Esp32Reading reading = new Esp32Reading(airTemperature, relativeHumidity, soilMoisture);
        inBlock = false;
        airTemperature = null;
        relativeHumidity = null;
        soilMoisture = null;
        if (reading.isEmpty()) {
            discardedBlocks++;
            log.warn("ESP32: block without any usable value dropped");
            return Optional.empty();
        }
        return Optional.of(reading);
    }

    public long discardedBlocks() {
        return discardedBlocks;
    }

    private void read(String label, String value) {
        switch (label) {
            case "temperatura exterior" -> airTemperature = within(value, MIN_TEMPERATURE, MAX_TEMPERATURE, label);
            case "humedad relativa" -> relativeHumidity = within(value, 0, 100, label);
            case "humedad del suelo" -> soilMoisture = within(value, 0, 100, label);
            default -> {
                // another label: ignored
            }
        }
    }

    private static Double within(String raw, double low, double high, String label) {
        Matcher number = NUMBER.matcher(raw);
        if (!number.matches()) {
            return null;  // nan, inf, empty or unreadable: not sent
        }
        double value = Double.parseDouble(number.group(1).replace(',', '.'));
        if (value < low || value > high) {
            log.warn("ESP32: {} = {} is outside {} to {}, left out", label, value, low, high);
            return null;
        }
        return value;
    }

    static String normalize(String line) {
        String plain = Normalizer.normalize(line.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

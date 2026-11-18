package com.agrosense.backend.pattern.behavioral.chain;

import java.text.Normalizer;
import java.util.Locale;

/** A line as the ESP32 wrote it and as the handlers compare it: without case, accents or repeated spaces. */
public record Line(String raw, String text) {

    public static Line of(String raw) {
        return new Line(raw, normalize(raw));
    }

    /** The label before the colon, or null when the line has none. */
    public String label() {
        int colon = text.indexOf(':');
        return colon > 0 ? text.substring(0, colon).strip() : null;
    }

    /** What follows the colon. */
    public String value() {
        return text.substring(text.indexOf(':') + 1).strip();
    }

    public static String normalize(String line) {
        String plain = Normalizer.normalize(line.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

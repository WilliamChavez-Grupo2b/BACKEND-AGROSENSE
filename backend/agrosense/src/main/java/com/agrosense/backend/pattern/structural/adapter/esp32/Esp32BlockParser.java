package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.pattern.behavioral.chain.BlockInProgress;
import com.agrosense.backend.pattern.behavioral.chain.Esp32LineHandlers;
import com.agrosense.backend.pattern.behavioral.chain.Line;
import com.agrosense.backend.pattern.behavioral.chain.LineHandler;
import com.agrosense.backend.pattern.structural.flyweight.Esp32ReadingPool;

import java.util.Optional;

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
 *
 * <p>Each line goes down a Chain of Responsibility (pattern/behavioral/chain, Esp32LineHandlers): header, blank line,
 * one handler per field and, last, the one that ignores any other line.
 */
public class Esp32BlockParser {

    public static final int MAX_LINE = 256;
    static final double MIN_TEMPERATURE = -20;
    static final double MAX_TEMPERATURE = 80;

    private final BlockInProgress block = new BlockInProgress();
    private final LineHandler chain = Esp32LineHandlers.chain(MAX_LINE, MIN_TEMPERATURE, MAX_TEMPERATURE);

    /** A reading when this line closed a block that had at least one usable field. */
    public Optional<Esp32Reading> accept(String line) {
        if (line == null) {
            return Optional.empty();
        }
        return chain.handle(Line.of(line), block);
    }

    /** Ends the block in progress, for a connection that dropped in the middle of one. */
    public Optional<Esp32Reading> finish() {
        return block.close();
    }

    public long discardedBlocks() {
        return block.discarded();
    }

    /** The Flyweight pool its readings come from. */
    public Esp32ReadingPool pool() {
        return block.pool();
    }

    static String normalize(String line) {
        return Line.normalize(line);
    }
}

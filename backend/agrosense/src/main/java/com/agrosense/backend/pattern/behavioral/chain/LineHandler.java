package com.agrosense.backend.pattern.behavioral.chain;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;

import java.util.Optional;

/**
 * Chain of Responsibility. Each line the ESP32 writes goes down a chain of handlers: the first that recognises it
 * handles it, and the others never see it. Esp32BlockParser builds the chain; a new kind of line is a new handler
 * linked in, not another branch in the parser.
 */
public abstract class LineHandler {

    private LineHandler next;

    /** Links the handlers in the order given and returns the first. */
    public static LineHandler chain(LineHandler first, LineHandler... rest) {
        LineHandler last = first;
        for (LineHandler handler : rest) {
            last.next = handler;
            last = handler;
        }
        return first;
    }

    /** A reading when this line closed a block; empty otherwise, or when no handler took the line. */
    public final Optional<Esp32Reading> handle(Line line, BlockInProgress block) {
        if (handles(line, block)) {
            return process(line, block);
        }
        return next == null ? Optional.empty() : next.handle(line, block);
    }

    protected abstract boolean handles(Line line, BlockInProgress block);

    protected abstract Optional<Esp32Reading> process(Line line, BlockInProgress block);
}

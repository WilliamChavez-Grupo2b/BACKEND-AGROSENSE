package com.agrosense.backend.pattern.behavioral.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Command, the invoker: it runs a watch and remembers the last runs (which one, when and how long it took). */
public class FieldWatchInvoker {

    public static final int HISTORY = 20;

    public record Run(String command, Instant startedAt, Duration took) {
    }

    private final Clock clock;
    private final Deque<Run> history = new ArrayDeque<>();

    public FieldWatchInvoker(Clock clock) {
        this.clock = clock;
    }

    public void run(FieldWatchCommand command) {
        Instant started = clock.instant();
        command.execute();
        remember(new Run(command.name(), started, Duration.between(started, clock.instant())));
    }

    /** The newest run first. */
    public synchronized List<Run> history() {
        return List.copyOf(history);
    }

    private synchronized void remember(Run run) {
        history.addFirst(run);
        while (history.size() > HISTORY) {
            history.removeLast();
        }
    }
}

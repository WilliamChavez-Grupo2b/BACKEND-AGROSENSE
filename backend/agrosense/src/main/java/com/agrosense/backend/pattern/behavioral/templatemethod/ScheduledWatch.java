package com.agrosense.backend.pattern.behavioral.templatemethod;

import com.agrosense.backend.pattern.behavioral.command.FieldWatchCommand;
import lombok.extern.slf4j.Slf4j;

/**
 * Template Method: every watch over the field runs the same way. {@link #execute()} fixes the steps (skip it when
 * it is off, run it, and log a failure without stopping the scheduler); each watch fills in only {@link #watch()} and,
 * if it can be turned off, {@link #isEnabled()}. Inside, {@link #guarded} keeps one failing crop or device from
 * stopping the round.
 */
@Slf4j
public abstract class ScheduledWatch implements FieldWatchCommand {

    @Override
    public final void execute() {
        if (!isEnabled()) {
            return;
        }
        guarded(name(), this::watch);
    }

    /** A hook: on unless the watch says otherwise. */
    protected boolean isEnabled() {
        return true;
    }

    protected abstract void watch();

    protected static void guarded(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.warn("Field watch failed on {}: {}", what, failure.getMessage());
        }
    }
}

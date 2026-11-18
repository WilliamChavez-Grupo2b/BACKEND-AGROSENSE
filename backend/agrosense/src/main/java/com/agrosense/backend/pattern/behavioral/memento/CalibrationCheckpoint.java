package com.agrosense.backend.pattern.behavioral.memento;

import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry.Snapshot;

/**
 * Memento, the caretaker: it keeps the registry's snapshot while a change runs and gives it back if the change fails.
 * It holds the snapshot without ever reading it.
 */
public final class CalibrationCheckpoint {

    private CalibrationCheckpoint() {
    }

    public static void guard(CalibrationRegistry registry, Runnable change) {
        Snapshot before = registry.save();
        try {
            change.run();
        } catch (RuntimeException failure) {
            registry.restore(before);
            throw failure;
        }
    }
}

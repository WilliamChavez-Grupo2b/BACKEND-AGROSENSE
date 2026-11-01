package com.agrosense.backend.pattern.structural.bridge;

/** The implementation side of the bridge: how water is started, whoever ordered it. */
public interface ActuatorDriver {

    void start(IrrigationCommand command);

    /** The longest run this actuator accepts; 0 when nothing limits it. */
    default int maxRunMinutes() {
        return 0;
    }
}

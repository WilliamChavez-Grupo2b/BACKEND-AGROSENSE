package com.agrosense.backend.pattern.structural.bridge;

/** The implementation side of the bridge: how water is started, whoever ordered it. */
public interface ActuatorDriver {

    void start(IrrigationCommand command);
}

package com.agrosense.backend.pattern.structural.bridge;

import lombok.RequiredArgsConstructor;

/** The relay is on the device: the order waits here and leaves in the answer to the device's next event. */
@RequiredArgsConstructor
public class RelayActuatorDriver implements ActuatorDriver {

    private final PendingIrrigationCommands pending;

    @Override
    public void start(IrrigationCommand command) {
        pending.offer(command);
    }
}

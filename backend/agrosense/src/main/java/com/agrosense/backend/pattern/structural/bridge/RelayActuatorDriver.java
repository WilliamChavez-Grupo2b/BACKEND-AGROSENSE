package com.agrosense.backend.pattern.structural.bridge;

import lombok.RequiredArgsConstructor;

/**
 * The relay is on the device: the order waits here and leaves in the answer to the device's next event. maxRunMinutes
 * mirrors MAX_PUMP_ON_MS in the firmware, which stops the pump whatever is asked of it.
 */
@RequiredArgsConstructor
public class RelayActuatorDriver implements ActuatorDriver {

    private final PendingIrrigationCommands pending;
    private final int maxRunMinutes;

    @Override
    public void start(IrrigationCommand command) {
        pending.offer(command);
    }

    @Override
    public int maxRunMinutes() {
        return maxRunMinutes;
    }
}

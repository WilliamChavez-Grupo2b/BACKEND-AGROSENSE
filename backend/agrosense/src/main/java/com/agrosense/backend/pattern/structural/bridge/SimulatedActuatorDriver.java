package com.agrosense.backend.pattern.structural.bridge;

import lombok.extern.slf4j.Slf4j;

/** The default: the irrigation is recorded and nothing in the field moves. */
@Slf4j
public class SimulatedActuatorDriver implements ActuatorDriver {

    @Override
    public void start(IrrigationCommand command) {
        log.info("Simulated irrigation for crop {}: {} min, ordered by {}", command.cropId(), command.minutes(),
                command.source());
    }
}

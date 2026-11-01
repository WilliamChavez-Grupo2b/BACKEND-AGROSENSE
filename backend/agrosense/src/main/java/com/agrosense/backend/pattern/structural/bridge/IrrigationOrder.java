package com.agrosense.backend.pattern.structural.bridge;

import com.agrosense.backend.models.Irrigation;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The abstraction side of the bridge: who orders the irrigation and on what terms. Any order works with any driver,
 * so two orders and two drivers are two pairs of classes instead of four.
 */
@RequiredArgsConstructor
public abstract class IrrigationOrder {

    private final ActuatorDriver driver;

    public final Optional<Irrigation> execute(UnaryOperator<Irrigation> store) {
        if (!authorized()) {
            return Optional.empty();
        }
        Irrigation stored = store.apply(record());
        driver.start(command());
        return Optional.of(stored);
    }

    protected abstract boolean authorized();

    protected abstract Irrigation record();

    protected abstract IrrigationCommand command();
}

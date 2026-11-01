package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

/** A reading that cannot be real, or a sensor stuck at the end of its scale. */
public class SensorFaultAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return context.fault() != null;
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.SENSOR_FAULT).automaticSeverity()
                .message("El sensor " + context.sensor().getSensorCode() + " " + context.fault() + " ("
                        + context.measured() + ").");
    }
}

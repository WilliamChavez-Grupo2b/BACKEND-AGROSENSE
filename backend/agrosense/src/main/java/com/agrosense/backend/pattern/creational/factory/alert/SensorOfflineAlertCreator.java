package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

public class SensorOfflineAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return context.deviceCode() != null && context.lastSeenAt() != null;
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.SENSOR_OFFLINE).automaticSeverity()
                .message("El dispositivo " + context.deviceCode() + " no reporta lecturas desde "
                        + context.lastSeenAt().withNano(0) + ".");
    }
}

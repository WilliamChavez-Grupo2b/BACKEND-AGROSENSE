package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

public class LowMoistureAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.SOIL_MOISTURE))
                && below(context.value(), context.crop().getHumidityMin());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.LOW_HUMIDITY).automaticSeverity()
                .message("La humedad está por debajo del mínimo del cultivo (" + context.measured() + ").");
    }
}

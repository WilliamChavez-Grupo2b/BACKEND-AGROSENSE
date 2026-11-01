package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

public class HighMoistureAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.SOIL_MOISTURE))
                && above(context.value(), context.crop().getHumidityMax());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.HIGH_HUMIDITY).automaticSeverity()
                .message("La humedad está por encima del máximo del cultivo (" + context.measured() + ").");
    }
}

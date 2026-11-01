package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

public class HighTemperatureAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.AIR_TEMPERATURE, SensorType.SOIL_TEMPERATURE))
                && above(context.value(), context.crop().getTempMax());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.HIGH_TEMPERATURE).automaticSeverity()
                .message("La temperatura está por encima del máximo del cultivo (" + context.measured() + ").");
    }
}

package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

public class LowTemperatureAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.AIR_TEMPERATURE, SensorType.SOIL_TEMPERATURE))
                && below(context.value(), context.crop().getTempMin());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.LOW_TEMPERATURE).automaticSeverity()
                .message("La temperatura está por debajo del mínimo del cultivo (" + context.measured() + ").");
    }
}

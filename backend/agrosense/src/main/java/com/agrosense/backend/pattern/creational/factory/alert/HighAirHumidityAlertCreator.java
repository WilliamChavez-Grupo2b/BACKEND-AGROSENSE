package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

/** The air's relative humidity above the crop's air range; never the soil's range, and nothing while there is no range. */
public class HighAirHumidityAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.RELATIVE_HUMIDITY))
                && above(context.value(), context.crop().getAirHumidityMax());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.HIGH_AIR_HUMIDITY).automaticSeverity()
                .message("La humedad del aire está por encima del máximo del cultivo (" + context.measured() + ").");
    }
}

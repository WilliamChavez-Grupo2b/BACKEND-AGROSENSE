package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

/** The air's relative humidity below the crop's air range; never the soil's range, and nothing while there is no range. */
public class LowAirHumidityAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.RELATIVE_HUMIDITY))
                && below(context.value(), context.crop().getAirHumidityMin());
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.LOW_AIR_HUMIDITY).automaticSeverity()
                .message("La humedad del aire está por debajo del mínimo del cultivo (" + context.measured() + ").");
    }
}

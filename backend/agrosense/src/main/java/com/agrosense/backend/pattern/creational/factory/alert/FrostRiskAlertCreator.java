package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.math.BigDecimal;
import java.util.Set;

/** Applies to the air temperature measured on the crop and to the forecast for its estate. */
public class FrostRiskAlertCreator extends AlertCreator {

    public static final BigDecimal FROST_THRESHOLD_C = new BigDecimal("2");

    @Override
    protected boolean applies(AlertContext context) {
        boolean airTemperature = context.forecastDate() != null
                || measures(context, Set.of(SensorType.AIR_TEMPERATURE));
        return airTemperature && context.value().compareTo(FROST_THRESHOLD_C) <= 0;
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        String message = context.forecastDate() == null
                ? "Riesgo de helada: la temperatura del aire bajó a " + context.measured() + "."
                : "Riesgo de helada: el pronóstico da una mínima de " + context.measured() + " el "
                        + context.forecastDate() + ".";
        return alertFor(context).type(AlertType.FROST_RISK).automaticSeverity().message(message);
    }
}

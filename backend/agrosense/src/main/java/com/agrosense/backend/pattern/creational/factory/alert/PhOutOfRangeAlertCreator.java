package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.util.Set;

public class PhOutOfRangeAlertCreator extends AlertCreator {

    @Override
    protected boolean applies(AlertContext context) {
        return measures(context, Set.of(SensorType.PH)) && (below(context.value(), context.crop().getPhMin())
                || above(context.value(), context.crop().getPhMax()));
    }

    @Override
    protected AlertBuilder createAlert(AlertContext context) {
        return alertFor(context).type(AlertType.PH_OUT_OF_RANGE).automaticSeverity()
                .message("El pH está fuera del rango del cultivo (" + context.measured() + ").");
    }
}

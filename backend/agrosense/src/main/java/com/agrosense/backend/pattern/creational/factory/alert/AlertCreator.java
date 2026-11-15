package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Factory Method. {@link #evaluate} is the same for every alert; each subclass decides whether its rule applies
 * and overrides {@link #createAlert} to make its own kind of alert.
 */
public abstract class AlertCreator {

    /** Checked in this order: frost must win over the plain low temperature it also is. */
    private static final List<AlertCreator> FOR_READINGS = List.of(new FrostRiskAlertCreator(),
            new LowTemperatureAlertCreator(), new HighTemperatureAlertCreator(), new LowMoistureAlertCreator(),
            new HighMoistureAlertCreator(), new LowAirHumidityAlertCreator(), new HighAirHumidityAlertCreator(),
            new PhOutOfRangeAlertCreator());

    public static Optional<Alert> firstFor(AlertContext context) {
        return FOR_READINGS.stream().map(creator -> creator.evaluate(context)).flatMap(Optional::stream).findFirst();
    }

    public final Optional<Alert> evaluate(AlertContext context) {
        return applies(context) ? Optional.of(createAlert(context).build()) : Optional.empty();
    }

    protected abstract boolean applies(AlertContext context);

    protected abstract AlertBuilder createAlert(AlertContext context);

    protected static AlertBuilder alertFor(AlertContext context) {
        return AlertBuilder.forCrop(context.crop()).sensor(context.sensor()).detectedValue(context.value());
    }

    protected static boolean measures(AlertContext context, Set<SensorType> types) {
        return context.sensor() != null && types.contains(context.sensor().getSensorType());
    }

    protected static boolean below(BigDecimal value, BigDecimal minimum) {
        return minimum != null && value.compareTo(minimum) < 0;
    }

    protected static boolean above(BigDecimal value, BigDecimal maximum) {
        return maximum != null && value.compareTo(maximum) > 0;
    }
}

package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;
import com.agrosense.backend.pattern.structural.composite.AlertRule;
import com.agrosense.backend.pattern.structural.composite.AlertRuleGroup;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * Factory Method. {@link #evaluate} is the same for every alert; each subclass decides whether its rule applies
 * and overrides {@link #createAlert} to make its own kind of alert.
 *
 * <p>Each creator is also the leaf of a Composite (pattern/structural/composite): the rules a reading is checked
 * against are an {@link AlertRuleGroup} of one group per measure, evaluated as if they were a single rule.
 */
public abstract class AlertCreator implements AlertRule {

    /** Checked in this order: frost must win over the plain low temperature it also is. */
    private static final AlertRuleGroup FOR_READINGS = AlertRuleGroup.of("lecturas",
            AlertRuleGroup.of("temperatura del aire", new FrostRiskAlertCreator(), new LowTemperatureAlertCreator(),
                    new HighTemperatureAlertCreator()),
            AlertRuleGroup.of("humedad del suelo", new LowMoistureAlertCreator(), new HighMoistureAlertCreator()),
            AlertRuleGroup.of("humedad del aire", new LowAirHumidityAlertCreator(),
                    new HighAirHumidityAlertCreator()),
            new PhOutOfRangeAlertCreator());

    public static Optional<Alert> firstFor(AlertContext context) {
        return FOR_READINGS.evaluate(context);
    }

    /** The rules every reading is checked against, as one tree. */
    public static AlertRuleGroup forReadings() {
        return FOR_READINGS;
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
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

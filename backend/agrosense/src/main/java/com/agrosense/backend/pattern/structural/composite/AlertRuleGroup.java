package com.agrosense.backend.pattern.structural.composite;

import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;

import java.util.List;
import java.util.Optional;

/**
 * Composite: a group of rules evaluated as one. It asks its rules in order and keeps the first alert, so a group can
 * hold single rules or other groups: the readings' rules are a group of one group per measure.
 */
public final class AlertRuleGroup implements AlertRule {

    private final String name;
    private final List<AlertRule> rules;

    public AlertRuleGroup(String name, List<AlertRule> rules) {
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("A group of alert rules needs at least one rule");
        }
        this.name = name;
        this.rules = List.copyOf(rules);
    }

    public static AlertRuleGroup of(String name, AlertRule... rules) {
        return new AlertRuleGroup(name, List.of(rules));
    }

    @Override
    public Optional<Alert> evaluate(AlertContext context) {
        for (AlertRule rule : rules) {
            Optional<Alert> alert = rule.evaluate(context);
            if (alert.isPresent()) {
                return alert;
            }
        }
        return Optional.empty();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int size() {
        return rules.stream().mapToInt(AlertRule::size).sum();
    }

    public List<AlertRule> rules() {
        return rules;
    }
}

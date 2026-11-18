package com.agrosense.backend.pattern.structural.composite;

import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;

import java.util.Optional;

/**
 * Composite, the component: a rule that may raise an alert for a reading. A single AlertCreator (the leaf) and an
 * AlertRuleGroup of rules (the composite) are both one: whoever evaluates them does not know which it holds.
 */
public interface AlertRule {

    Optional<Alert> evaluate(AlertContext context);

    String name();

    /** How many single rules it holds: 1 for a leaf. */
    default int size() {
        return 1;
    }
}

package com.agrosense.backend;

import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.AlertCreator;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32BlockParser;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.composite.AlertRule;
import com.agrosense.backend.pattern.structural.composite.AlertRuleGroup;
import com.agrosense.backend.pattern.structural.flyweight.Esp32ReadingPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The two structural patterns added last: Composite (alert rules) and Flyweight (the ESP32's readings). */
class CompositeAndFlyweightTests {

    /** A rule that records it was asked and answers what it is told. */
    private static AlertRule rule(String name, Optional<Alert> answer, List<String> asked) {
        return new AlertRule() {
            @Override
            public Optional<Alert> evaluate(AlertContext context) {
                asked.add(name);
                return answer;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    // Composite

    @Test
    void theReadingsRulesAreOneTreeOfOneGroupPerMeasure() {
        AlertRuleGroup readings = AlertCreator.forReadings();
        assertThat(readings.name()).isEqualTo("lecturas");
        assertThat(readings.rules()).extracting(AlertRule::name)
                .containsExactly("temperatura del aire", "humedad del suelo", "humedad del aire",
                        "PhOutOfRangeAlertCreator");
        assertThat(readings.size()).as("the eight single rules, counted through the groups").isEqualTo(8);
        assertThat(((AlertRuleGroup) readings.rules().get(0)).rules()).extracting(AlertRule::name)
                .as("frost first: it must win over the plain low temperature")
                .startsWith("FrostRiskAlertCreator", "LowTemperatureAlertCreator");
    }

    @Test
    void aGroupIsEvaluatedLikeASingleRuleAndStopsAtTheFirstAlert() {
        List<String> asked = new ArrayList<>();
        Alert found = mock(Alert.class);
        AlertRuleGroup tree = AlertRuleGroup.of("raíz",
                AlertRuleGroup.of("vacío", rule("a", Optional.empty(), asked), rule("b", Optional.empty(), asked)),
                AlertRuleGroup.of("con alerta", rule("c", Optional.of(found), asked)),
                rule("d", Optional.of(mock(Alert.class)), asked));

        assertThat(tree.evaluate(null)).containsSame(found);
        assertThat(asked).as("the rule after the first alert is never asked").containsExactly("a", "b", "c");
        assertThat(tree.size()).isEqualTo(4);

        asked.clear();
        assertThat(AlertRuleGroup.of("nada", rule("e", Optional.empty(), asked)).evaluate(null)).isEmpty();
        assertThatThrownBy(() -> new AlertRuleGroup("sin reglas", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Flyweight

    @Test
    void equalBlocksFromTheEsp32AreOneSharedReading() {
        Esp32BlockParser parser = new Esp32BlockParser();
        Esp32Reading first = parse(parser, "24.00", "58.00", "31.00");
        Esp32Reading second = parse(parser, "24.00", "58.00", "31.00");
        Esp32Reading other = parse(parser, "24.00", "59.00", "31.00");

        assertThat(second).isSameAs(first);
        assertThat(other).isNotSameAs(first).isNotEqualTo(first);
        assertThat(parser.pool().size()).isEqualTo(2);
        assertThat(parser.pool().shared()).isEqualTo(1);
        assertThat(parser.pool().requests()).isEqualTo(3);
    }

    @Test
    void thePoolKeepsOnlyTheValuesUsedLast() {
        Esp32ReadingPool pool = new Esp32ReadingPool();
        Esp32Reading kept = pool.of(20.0, 50.0, 30.0);
        for (int i = 0; i < Esp32ReadingPool.CAPACITY + 50; i++) {
            pool.of(20.0, 50.0, (double) i);
            pool.of(20.0, 50.0, 30.0);  // used again and again: it stays
        }
        assertThat(pool.size()).isEqualTo(Esp32ReadingPool.CAPACITY);
        assertThat(pool.of(20.0, 50.0, 30.0)).isSameAs(kept);
    }

    private static Esp32Reading parse(Esp32BlockParser parser, String temperature, String humidity, String soil) {
        parser.accept("===== PLANTICAS =====");
        parser.accept("Temperatura exterior: " + temperature + " C");
        parser.accept("Humedad relativa: " + humidity + " %");
        parser.accept("Humedad del suelo: " + soil + " %");
        return parser.accept("").orElseThrow();
    }
}

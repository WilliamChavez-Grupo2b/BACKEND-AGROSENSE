package com.agrosense.backend.pattern.behavioral.interpreter;

import com.agrosense.backend.domain.enums.SensorType;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interpreter: the tree of a watering rule, such as {@code suelo < 40 y aire < 60}. Each node interprets itself
 * against the field's conditions: a comparison reads one measure, {@code y} and {@code o} combine their two sides.
 * RuleParser builds the tree from the text.
 */
public sealed interface RuleExpression {

    /** Whether the rule holds. A measure the conditions lack makes its comparison false, never true. */
    boolean interpret(Map<SensorType, BigDecimal> conditions);

    /** The rule as it was written, normalised: {@code suelo < 40 y aire < 60}. */
    String text();

    /** The measures the rule reads, to name their values when it holds. */
    Set<Variable> variables();

    enum Variable {
        SUELO(SensorType.SOIL_MOISTURE, "%"),
        TEMPERATURA(SensorType.AIR_TEMPERATURE, "°C"),
        AIRE(SensorType.RELATIVE_HUMIDITY, "%");

        private final SensorType type;
        private final String unit;

        Variable(SensorType type, String unit) {
            this.type = type;
            this.unit = unit;
        }

        public SensorType type() {
            return type;
        }

        public String unit() {
            return unit;
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Operator {
        LESS_OR_EQUAL("<="), GREATER_OR_EQUAL(">="), LESS("<"), GREATER(">");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        boolean test(BigDecimal value, BigDecimal limit) {
            int comparison = value.compareTo(limit);
            return switch (this) {
                case LESS -> comparison < 0;
                case LESS_OR_EQUAL -> comparison <= 0;
                case GREATER -> comparison > 0;
                case GREATER_OR_EQUAL -> comparison >= 0;
            };
        }
    }

    /** A terminal expression: {@code suelo < 40}. */
    record Comparison(Variable variable, Operator operator, BigDecimal limit) implements RuleExpression {

        @Override
        public boolean interpret(Map<SensorType, BigDecimal> conditions) {
            BigDecimal value = conditions.get(variable.type());
            return value != null && operator.test(value, limit);
        }

        @Override
        public String text() {
            return variable.word() + " " + operator.symbol() + " " + limit.stripTrailingZeros().toPlainString();
        }

        @Override
        public Set<Variable> variables() {
            return Set.of(variable);
        }
    }

    /** A non-terminal expression: both sides must hold. */
    record And(RuleExpression left, RuleExpression right) implements RuleExpression {

        @Override
        public boolean interpret(Map<SensorType, BigDecimal> conditions) {
            return left.interpret(conditions) && right.interpret(conditions);
        }

        @Override
        public String text() {
            return side(left) + " y " + side(right);
        }

        @Override
        public Set<Variable> variables() {
            return both(left, right);
        }

        private static String side(RuleExpression side) {
            return side instanceof Or ? "(" + side.text() + ")" : side.text();
        }
    }

    /** A non-terminal expression: either side is enough. */
    record Or(RuleExpression left, RuleExpression right) implements RuleExpression {

        @Override
        public boolean interpret(Map<SensorType, BigDecimal> conditions) {
            return left.interpret(conditions) || right.interpret(conditions);
        }

        @Override
        public String text() {
            return left.text() + " o " + right.text();
        }

        @Override
        public Set<Variable> variables() {
            return both(left, right);
        }
    }

    private static Set<Variable> both(RuleExpression left, RuleExpression right) {
        return Stream.concat(left.variables().stream(), right.variables().stream())
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(Variable.class)));
    }
}

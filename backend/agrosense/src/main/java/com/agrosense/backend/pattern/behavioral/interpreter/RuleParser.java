package com.agrosense.backend.pattern.behavioral.interpreter;

import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.And;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Comparison;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Operator;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Or;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Variable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the tree of a watering rule from its text. The grammar, where {@code y} binds tighter than {@code o}:
 *
 * <pre>
 * regla       := conjuncion ( "o" conjuncion )*
 * conjuncion  := factor ( "y" factor )*
 * factor      := "(" regla ")" | medida operador numero
 * medida      := "suelo" | "temperatura" | "aire"
 * operador    := "&lt;" | "&lt;=" | "&gt;" | "&gt;="
 * </pre>
 *
 * A rule that does not follow it is refused with the reason, so a mistyped ALERTS_WATERING_RULE stops the start-up.
 */
public final class RuleParser {

    private static final Pattern TOKEN = Pattern.compile("\\s*(<=|>=|<|>|\\(|\\)|-?\\d+(?:[.,]\\d+)?|[a-záéíóúñ]+)");

    private final List<String> tokens;
    private int position;

    private RuleParser(List<String> tokens) {
        this.tokens = tokens;
    }

    public static RuleExpression parse(String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("La regla de riego está vacía");
        }
        RuleParser parser = new RuleParser(tokenize(rule.strip().toLowerCase(Locale.ROOT)));
        RuleExpression expression = parser.rule();
        if (parser.position < parser.tokens.size()) {
            throw new IllegalArgumentException("La regla de riego sobra desde «" + parser.peek() + "»: " + rule);
        }
        return expression;
    }

    private static List<String> tokenize(String rule) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(rule);
        int end = 0;
        while (end < rule.length() && matcher.find(end) && matcher.start() == end) {
            tokens.add(matcher.group(1));
            end = matcher.end();
        }
        if (!rule.substring(end).isBlank()) {
            throw new IllegalArgumentException("La regla de riego no se entiende desde «" + rule.substring(end).strip()
                    + "»");
        }
        return tokens;
    }

    private RuleExpression rule() {
        RuleExpression expression = conjunction();
        while ("o".equals(peek())) {
            position++;
            expression = new Or(expression, conjunction());
        }
        return expression;
    }

    private RuleExpression conjunction() {
        RuleExpression expression = factor();
        while ("y".equals(peek())) {
            position++;
            expression = new And(expression, factor());
        }
        return expression;
    }

    private RuleExpression factor() {
        if ("(".equals(peek())) {
            position++;
            RuleExpression inside = rule();
            expect(")");
            return inside;
        }
        Variable variable = variable(next("una medida (suelo, temperatura o aire)"));
        Operator operator = operator(next("un operador (<, <=, > o >=)"));
        return new Comparison(variable, operator, number(next("un número")));
    }

    private static Variable variable(String word) {
        for (Variable variable : Variable.values()) {
            if (variable.word().equals(word)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("«" + word + "» no es una medida: usa suelo, temperatura o aire");
    }

    private static Operator operator(String symbol) {
        for (Operator operator : Operator.values()) {
            if (operator.symbol().equals(symbol)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("«" + symbol + "» no es un operador: usa <, <=, > o >=");
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("«" + text + "» no es un número");
        }
    }

    private void expect(String token) {
        if (!token.equals(next("«" + token + "»"))) {
            throw new IllegalArgumentException("Falta «" + token + "» en la regla de riego");
        }
    }

    private String next(String what) {
        if (position >= tokens.size()) {
            throw new IllegalArgumentException("La regla de riego termina antes de tiempo: falta " + what);
        }
        return tokens.get(position++);
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }
}

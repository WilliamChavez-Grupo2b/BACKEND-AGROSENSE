package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Variable;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleParser;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The watering rule (ALERTS_WATERING_RULE), read once by the Interpreter (pattern/behavioral/interpreter) and checked
 * against the stored values of every device event: when it holds, the crop gets a «Riego recomendado» alert. An open
 * one is not repeated. A blank rule turns this off; a mistyped one stops the start-up with the reason.
 */
@Slf4j
@Service
public class WateringRuleService {

    private final RuleExpression rule;
    private final CropRepository cropRepository;
    private final SensorFacade sensorFacade;

    @Autowired
    public WateringRuleService(@Value("${alerts.watering-rule:}") String rule, CropRepository cropRepository,
            SensorFacade sensorFacade) {
        this.rule = rule == null || rule.isBlank() ? null : RuleParser.parse(rule);
        this.cropRepository = cropRepository;
        this.sensorFacade = sensorFacade;
        if (this.rule != null) {
            log.info("Watering rule: {}", this.rule.text());
        }
    }

    /** A rule that never holds, for the facades built by hand in tests. */
    public static WateringRuleService off() {
        return new WateringRuleService(null, null, null);
    }

    public Optional<String> rule() {
        return Optional.ofNullable(rule).map(RuleExpression::text);
    }

    /** The alert raised for the event a device sent, if the rule held and none was open. */
    @Transactional
    public Optional<Alert> check(Map<String, Object> answer) {
        if (rule == null || !(answer.get("event") instanceof Map<?, ?> event)
                || !(event.get("cropId") instanceof Integer cropId)
                || !(event.get("readings") instanceof List<?> readings)) {
            return Optional.empty();
        }
        Map<SensorType, BigDecimal> conditions = conditionsOf(readings);
        if (!rule.interpret(conditions)) {
            return Optional.empty();
        }
        return cropRepository.findById(cropId).flatMap(crop -> sensorFacade.raiseAlert(crop,
                AlertType.RECOMMENDED_WATERING, message(conditions), Severity.MEDIUM,
                conditions.get(SensorType.SOIL_MOISTURE)));
    }

    private static Map<SensorType, BigDecimal> conditionsOf(List<?> readings) {
        Map<SensorType, BigDecimal> conditions = new EnumMap<>(SensorType.class);
        for (Object item : readings) {
            if (item instanceof SensorReadingResponse reading && reading.getValue() != null
                    && ValidationDecorator.QUALITY_OK.equals(reading.getQuality())) {
                conditions.put(SensorType.valueOf(reading.getSensorType()), reading.getValue());
            }
        }
        return conditions;
    }

    private String message(Map<SensorType, BigDecimal> conditions) {
        String values = rule.variables().stream()
                .map(variable -> describe(variable, conditions.get(variable.type())))
                .collect(Collectors.joining(", "));
        return "Conviene regar: se cumple la regla de riego «" + rule.text() + "» (" + values + ").";
    }

    private static String describe(Variable variable, BigDecimal value) {
        String shown = value == null ? "sin dato"
                : value.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " " + variable.unit();
        return variable.word() + " " + shown;
    }
}

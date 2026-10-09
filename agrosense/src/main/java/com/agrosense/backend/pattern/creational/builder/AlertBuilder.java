package com.agrosense.backend.pattern.creacional.builder;

import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.domain.enums.Alert_type;
import com.agrosense.backend.domain.model.Alert;
import com.agrosense.backend.domain.model.Cultive;
import com.agrosense.backend.domain.model.Sensor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class AlertBuilder {

    private Cultive    cultive;
    private Sensor     sensor;
    private TypeAlert  typeAlert;
    private Severity   severity  = Severity.MIDLOW;
    private String     message;
    private BigDecimal ValueDetected;

    public AlertBuilder ToCultive(Cultive cultive) {
        this.cultive = cultive;
        return this;
    }

    public AlertBuilder ToSensor(Sensor sensor) {
        this.sensor = sensor;
        return this;
    }

    public AlertBuilder Type(TypeAlert  type) {
        this.typeAlert = type;
        return this;
    }

    public AlertBuilder ToSeverity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public AlertBuilder ToMessage(String message) {
        this.message = message;
        return this;
    }

    public AlertBuilder ToValue(BigDecimal value) {
        this.ValueDetected = value;
        return this;
    }

    /**
     * Determina la severity automáticamente
     * según el type de alerta y el value detectado.
     */
    public AlertBuilder SeverityAutomatic() {
        if (typeAlert == null || ValueDetected == null) return this;
        this.severity = switch (typeAlert) {
            case LOW_HUMIDITY -> {
                double v = ValueDetected.doubleValue();
                yield v < 20 ? Severity.VERY:HIGH
                    : v < 30 ? Severity.HIGH
                    : Severity.MIDLOW;
            }
            case HIGH_TEMPERATURE -> {
                double v = ValueDetected.doubleValue();
                yield v > 45 ? Severity.VERY:HIGH
                    : v > 40 ? Severity.HIGH
                    : Severity.MIDLOW;
            }
            case PH_OUT_RANGER  -> Severity.HIGH;
            case PLAGUE_DETECTED -> Severity.VERY:HIGH;
            default              -> Severity.MIDLOW;
        };
        return this;
    }

    public Alert build() {
        validar();
        Alert alert = AlertT.builder()
            .cultive(cultive)
            .sensor(sensor)
            .typeAlert(typeAlert)
            .severity(severity)
            .message(message)
            .ValueDetected(ValueDetected)
            .atendida(false)
            .build();
        resetear();
        return alert;
    }

    private void check() {
        if (cultive   == null) throw new IllegalStateException("Cultive requiered");
        if (typeAlert == null) throw new IllegalStateException("TypeAlert  requiered");
        if (message == null || message.isBlank())
            throw new IllegalStateException("Requiered");
    }

    // Resetea el builder para reutilizarlo
    private void reset() {
        this.cultive        = null;
        this.sensor         = null;
        this.typeAlert     = null;
        this.severity      = Severity.MIDLOW;
        this.message        = null;
        this.ValueDetected = null;
    }
}
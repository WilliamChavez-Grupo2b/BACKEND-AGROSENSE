package com.agrosense.backend.pattern.behavioral.visitor;

import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.AirTemperature;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.RelativeHumidity;
import com.agrosense.backend.pattern.behavioral.visitor.Esp32Measure.SoilMoisture;

import java.util.Locale;

/** Says a measure in words, as the log shows it: «suelo 32,5 %». */
public class MeasureDescriber implements Esp32MeasureVisitor<String> {

    private static final Locale SPANISH = Locale.forLanguageTag("es-CO");

    @Override
    public String visitAirTemperature(AirTemperature measure) {
        return "temperatura " + format(measure.value()) + " °C";
    }

    @Override
    public String visitRelativeHumidity(RelativeHumidity measure) {
        return "aire " + format(measure.value()) + " %";
    }

    @Override
    public String visitSoilMoisture(SoilMoisture measure) {
        return "suelo " + format(measure.value()) + " %";
    }

    private static String format(double value) {
        return String.format(SPANISH, "%.1f", value);
    }
}

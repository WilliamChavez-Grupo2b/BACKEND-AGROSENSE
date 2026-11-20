package com.agrosense.frontend.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The route of the ESP32's soil moisture: what each reading consumed and how much is consumed per hour. */
class SoilTrailTests {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 20, 12, 0);

    private static SoilTrail.Reading at(int minutesBefore, String value) {
        return new SoilTrail.Reading(NOON.minusMinutes(minutesBefore), new BigDecimal(value));
    }

    @Test
    void eachReadingSaysHowMuchTheSoilConsumedSinceTheOneBefore() {
        SoilTrail trail = SoilTrail.of("ESP32-PLANTICAS", "Café", new BigDecimal("40"), new BigDecimal("80"),
                List.of(at(0, "35.00"), at(1, "35.30"), at(2, "35.30"), at(3, "36.20")));

        assertThat(trail.steps()).extracting(SoilTrail.Step::changeText)
                .containsExactly("bajó 0,30 %", "sin cambio", "bajó 0,90 %", "primera de la lista");
        assertThat(trail.steps()).extracting(SoilTrail.Step::tone)
                .containsExactly("warning", "neutral", "warning", "neutral");
        assertThat(trail.steps().get(0).valueText()).isEqualTo("35,0 %");
        assertThat(trail.steps().get(0).inRange()).as("below the crop's minimum").isFalse();
        assertThat(trail.summary()).isEqualTo("En 3 min la humedad del suelo bajó 1,20 %: consume 24,00 % por hora.");
        assertThat(trail.rangeText()).isEqualTo("40 – 80 %");
        assertThat(trail.optimum()).isEqualByComparingTo("60");
    }

    @Test
    void aPauseOfTheBoardIsNotConsumptionAndTheSummaryOnlyCoversTheReadingsSinceIt() {
        // The demo stopped from 11:55 to 14:26: the soil did not lose 28 % in that time, nobody was measuring.
        SoilTrail afterPause = SoilTrail.of("ESP32-PLANTICAS", "Café", new BigDecimal("40"), new BigDecimal("80"),
                List.of(at(0, "7.50"), at(151, "35.48"), at(152, "35.80")));
        assertThat(afterPause.steps().get(0).changeText()).isEqualTo("tras 2 h sin datos");
        assertThat(afterPause.steps().get(0).tone()).isEqualTo("neutral");
        assertThat(afterPause.steps().get(0).icon()).isEqualTo("bi-clock-history");
        assertThat(afterPause.steps().get(1).changeText()).isEqualTo("bajó 0,32 %");
        assertThat(afterPause.summary()).isEqualTo("Volvió a medir a las 12:00, después de 2 h sin datos.");

        SoilTrail measuringAgain = SoilTrail.of("ESP32-PLANTICAS", "Café", null, null,
                List.of(at(0, "7.00"), at(1, "7.50"), at(152, "35.80")));
        assertThat(measuringAgain.summary()).as("only the minute since the pause counts")
                .isEqualTo("En 1 min la humedad del suelo bajó 0,50 %: consume 30,00 % por hora.");
    }

    @Test
    void aRiseIsNotConsumptionAndOneReadingHasNoSummary() {
        SoilTrail risen = SoilTrail.of("ESP32-PLANTICAS", "Café", null, null,
                List.of(at(0, "50.00"), at(1, "45.00")));
        assertThat(risen.steps().get(0).changeText()).isEqualTo("subió 5,00 %");
        assertThat(risen.steps().get(0).tone()).isEqualTo("info");
        assertThat(risen.summary()).isEqualTo("En 1 min la humedad del suelo subió 5,00 %.");
        assertThat(risen.rangeText()).isNull();

        SoilTrail single = SoilTrail.of("ESP32-PLANTICAS", "Café", null, null, List.of(at(0, "40.00")));
        assertThat(single.summary()).isNull();
        assertThat(single.hasSteps()).isTrue();
        assertThat(SoilTrail.of("ESP32-PLANTICAS", "Café", null, null, List.of()).hasSteps()).isFalse();
    }
}

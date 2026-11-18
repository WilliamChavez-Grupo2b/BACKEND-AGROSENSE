package com.agrosense.backend;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry.Calibration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Memento: a calibration that fails to load leaves the previous one whole, never half replaced. */
class CalibrationMementoTests {

    private final CalibrationRegistry registry = CalibrationRegistry.INSTANCE;

    @AfterEach
    void neutral() {
        registry.clear();
    }

    @Test
    void aCalibrationWithABadEntryLeavesThePreviousOneUntouched() {
        registry.load("SOIL_MOISTURE=-1.5:1");

        assertThatThrownBy(() -> registry.load("PH=0.1:1.02, AIR_TEMPERATURE=oops"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.of(SensorType.SOIL_MOISTURE).offset()).isEqualByComparingTo("-1.5");
        assertThat(registry.of(SensorType.PH)).as("the good entry before the bad one is not kept")
                .isEqualTo(Calibration.NONE);
    }
}

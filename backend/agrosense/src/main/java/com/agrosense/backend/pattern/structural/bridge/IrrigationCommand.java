package com.agrosense.backend.pattern.structural.bridge;

import java.math.BigDecimal;

public record IrrigationCommand(Integer cropId, int minutes, String source, BigDecimal confidence) {

    public static final String MANUAL = "MANUAL";
    public static final String AI = "AI";
}

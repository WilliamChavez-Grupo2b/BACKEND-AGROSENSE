package com.agrosense.backend.pattern.structural.adapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** What AgroSense needs from a weather service, whichever service it is. */
public interface WeatherProvider {

    record Outlook(LocalDate coldestDay, BigDecimal minimumTemperature, Integer rainProbability, BigDecimal rainMm) {
    }

    Optional<Outlook> outlook(BigDecimal latitude, BigDecimal longitude);
}

package com.agrosense.backend.pattern.structural.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A reading in the application's own terms, whatever channel it came from (MQTT or REST). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorReadingData {

    private String sensorCode;
    private BigDecimal value;
    private String unit;
    private LocalDateTime recordedAt;
}

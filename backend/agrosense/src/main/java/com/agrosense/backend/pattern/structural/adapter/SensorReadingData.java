package com.agrosense.backend.pattern.structural.adapter;

import com.agrosense.backend.domain.enums.ReadingSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorReadingData {

    private String sensorCode;
    private BigDecimal value;
    private String unit;
    private LocalDateTime recordedAt;
    /** Where the reading came from; null is DEVICE. */
    private ReadingSource source;
}

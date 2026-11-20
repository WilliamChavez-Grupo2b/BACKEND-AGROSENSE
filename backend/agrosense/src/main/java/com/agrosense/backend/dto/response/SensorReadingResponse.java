package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.SensorReading;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class SensorReadingResponse {

    private Long idReading;
    private Integer idSensor;
    private String sensorCode;
    private String sensorType;
    private BigDecimal value;
    private String unit;
    private String quality;
    private LocalDateTime recordedAt;
    /** DEVICE, ESP32_TCP, SIMULATED, SEED or MANUAL (sensor_readings.source). */
    private String source;

    public static SensorReadingResponse from(SensorReading reading) {
        return SensorReadingResponse.builder()
                .idReading(reading.getIdReading())
                .idSensor(reading.getSensor().getIdSensor())
                .sensorCode(reading.getSensor().getSensorCode())
                .sensorType(reading.getSensor().getSensorType().name())
                .value(reading.getValue())
                .unit(reading.getUnit())
                .quality(reading.getQuality())
                .recordedAt(reading.getRecordedAt())
                .source(reading.getSource() == null ? null : reading.getSource().name())
                .build();
    }
}

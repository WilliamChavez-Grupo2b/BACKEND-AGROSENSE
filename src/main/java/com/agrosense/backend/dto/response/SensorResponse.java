package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Sensor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SensorResponse {

    private Integer idSensor;
    private Integer idCrop;
    private String sensorCode;
    private String sensorType;
    private String location;
    private Boolean active;
    private LocalDateTime lastReadingAt;

    public static SensorResponse from(Sensor sensor) {
        return SensorResponse.builder()
                .idSensor(sensor.getIdSensor())
                .idCrop(sensor.getCrop().getIdCrop())
                .sensorCode(sensor.getSensorCode())
                .sensorType(sensor.getSensorType().name())
                .location(sensor.getLocation())
                .active(sensor.getActive())
                .lastReadingAt(sensor.getLastReadingAt())
                .build();
    }
}

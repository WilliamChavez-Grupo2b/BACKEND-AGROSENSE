package com.agrosense.backend.dto.request;

import com.agrosense.backend.domain.enums.SensorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SensorRequest {

    @NotNull(message = "El cultivo es obligatorio.")
    private Integer cropId;

    @NotNull(message = "El tipo de sensor es obligatorio.")
    private SensorType sensorType;

    @NotBlank(message = "El código del sensor es obligatorio.")
    @Size(max = 255, message = "El código del sensor es demasiado largo.")
    private String sensorCode;

    @Size(max = 255, message = "La ubicación es demasiado larga.")
    private String location;
}

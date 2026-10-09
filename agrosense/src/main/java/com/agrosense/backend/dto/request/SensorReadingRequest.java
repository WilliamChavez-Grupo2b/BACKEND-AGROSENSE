package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SensorReadingRequest {

    @NotBlank(message = "El código del sensor es obligatorio.")
    @Size(max = 255, message = "El código del sensor es demasiado largo.")
    private String sensorCode;

    @NotNull(message = "El valor es obligatorio.")
    private BigDecimal value;

    @Size(max = 20, message = "La unidad es demasiado larga.")
    private String unit;
}

package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Data to register an irrigation cycle started by a user. */
@Data
public class RiegoRequest {

    @NotNull(message = "El cultivo es obligatorio.")
    private Integer cropId;

    @NotNull(message = "La cantidad de agua es obligatoria.")
    @Positive(message = "La cantidad de agua debe ser mayor que cero.")
    @Digits(integer = 8, fraction = 2, message = "La cantidad de agua admite hasta 8 enteros y 2 decimales.")
    private BigDecimal waterLiters;

    @NotNull(message = "La duración es obligatoria.")
    @Positive(message = "La duración debe ser mayor que cero.")
    @Max(value = 1440, message = "La duración no puede superar las 24 horas.")
    private Integer durationMinutes;

    @NotBlank(message = "El motivo es obligatorio.")
    @Size(max = 255, message = "El motivo es demasiado largo.")
    private String reason;
}

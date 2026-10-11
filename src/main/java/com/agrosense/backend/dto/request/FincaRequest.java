package com.agrosense.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Data to register an estate. The coordinates are optional, but they only make sense as a pair. */
@Data
public class FincaRequest {

    @NotBlank(message = "El nombre de la finca es obligatorio.")
    @Size(max = 255, message = "El nombre de la finca es demasiado largo.")
    private String name;

    @NotBlank(message = "La ubicación es obligatoria.")
    @Size(max = 255, message = "La ubicación es demasiado larga.")
    private String location;

    @DecimalMin(value = "-90", message = "La latitud debe estar entre -90 y 90.")
    @DecimalMax(value = "90", message = "La latitud debe estar entre -90 y 90.")
    private BigDecimal latitude;

    @DecimalMin(value = "-180", message = "La longitud debe estar entre -180 y 180.")
    @DecimalMax(value = "180", message = "La longitud debe estar entre -180 y 180.")
    private BigDecimal longitude;

    @NotNull(message = "El área es obligatoria.")
    @Positive(message = "El área debe ser mayor que cero.")
    @Digits(integer = 8, fraction = 2, message = "El área admite hasta 8 enteros y 2 decimales.")
    private BigDecimal areaHa;

    @JsonIgnore
    @AssertTrue(message = "La latitud y la longitud deben enviarse juntas.")
    public boolean isCoordinatesComplete() {
        return (latitude == null) == (longitude == null);
    }
}

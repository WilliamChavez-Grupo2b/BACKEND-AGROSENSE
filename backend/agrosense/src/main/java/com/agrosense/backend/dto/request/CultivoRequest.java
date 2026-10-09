package com.agrosense.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data to register a crop on an estate. Each range (humidity, temperature, pH) is optional as a whole:
 * leave both ends out to get the default range, or send both with the minimum below the maximum.
 */
@Data
public class CultivoRequest {

    @NotNull(message = "La finca es obligatoria.")
    private Integer estateId;

    @NotBlank(message = "El nombre del cultivo es obligatorio.")
    @Size(max = 255, message = "El nombre del cultivo es demasiado largo.")
    private String name;

    @Size(max = 255, message = "La variedad es demasiado larga.")
    private String variety;

    private LocalDate sowingDate;

    @DecimalMin(value = "0", message = "La humedad debe estar entre 0 y 100.")
    @DecimalMax(value = "100", message = "La humedad debe estar entre 0 y 100.")
    @Digits(integer = 3, fraction = 2, message = "La humedad admite hasta 2 decimales.")
    private BigDecimal humidityMin;

    @DecimalMin(value = "0", message = "La humedad debe estar entre 0 y 100.")
    @DecimalMax(value = "100", message = "La humedad debe estar entre 0 y 100.")
    @Digits(integer = 3, fraction = 2, message = "La humedad admite hasta 2 decimales.")
    private BigDecimal humidityMax;

    @DecimalMin(value = "-50", message = "La temperatura debe estar entre -50 y 80.")
    @DecimalMax(value = "80", message = "La temperatura debe estar entre -50 y 80.")
    @Digits(integer = 3, fraction = 2, message = "La temperatura admite hasta 2 decimales.")
    private BigDecimal tempMin;

    @DecimalMin(value = "-50", message = "La temperatura debe estar entre -50 y 80.")
    @DecimalMax(value = "80", message = "La temperatura debe estar entre -50 y 80.")
    @Digits(integer = 3, fraction = 2, message = "La temperatura admite hasta 2 decimales.")
    private BigDecimal tempMax;

    @DecimalMin(value = "0", message = "El pH debe estar entre 0 y 14.")
    @DecimalMax(value = "14", message = "El pH debe estar entre 0 y 14.")
    @Digits(integer = 2, fraction = 2, message = "El pH admite hasta 2 decimales.")
    private BigDecimal phMin;

    @DecimalMin(value = "0", message = "El pH debe estar entre 0 y 14.")
    @DecimalMax(value = "14", message = "El pH debe estar entre 0 y 14.")
    @Digits(integer = 2, fraction = 2, message = "El pH admite hasta 2 decimales.")
    private BigDecimal phMax;

    @JsonIgnore
    @AssertTrue(message = "El rango de humedad necesita un mínimo menor que el máximo.")
    public boolean isHumidityRangeValid() {
        return isValidRange(humidityMin, humidityMax);
    }

    @JsonIgnore
    @AssertTrue(message = "El rango de temperatura necesita un mínimo menor que el máximo.")
    public boolean isTempRangeValid() {
        return isValidRange(tempMin, tempMax);
    }

    @JsonIgnore
    @AssertTrue(message = "El rango de pH necesita un mínimo menor que el máximo.")
    public boolean isPhRangeValid() {
        return isValidRange(phMin, phMax);
    }

    private static boolean isValidRange(BigDecimal minimum, BigDecimal maximum) {
        if (minimum == null || maximum == null) {
            return minimum == null && maximum == null;
        }
        return minimum.compareTo(maximum) < 0;
    }
}

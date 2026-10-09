package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class LectureRequest {

    @NotBlank(message = "Code Sensor is Obligatory")
    private String code;

    @NotNull(message = "Value is obligatory")
    private BigDecimal value;

    private String unique;
}
package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DeviceRequest {

    @NotNull(message = "El cultivo es obligatorio.")
    private Integer cropId;

    @NotBlank(message = "El código del dispositivo es obligatorio.")
    @Pattern(regexp = "\\s*[A-Za-z0-9_-]{3,64}\\s*",
            message = "El código del dispositivo debe tener de 3 a 64 letras, números, guiones o guiones bajos.")
    private String deviceCode;
}

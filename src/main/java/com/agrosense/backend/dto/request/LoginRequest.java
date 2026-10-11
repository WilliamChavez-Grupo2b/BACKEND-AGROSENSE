package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "El correo es obligatorio.")
    @Email(message = "El correo no es válido.")
    @Size(max = 255, message = "El correo es demasiado largo.")
    private String email;

    @NotBlank(message = "La contraseña es obligatoria.")
    @Size(max = 72, message = "La contraseña es demasiado larga.")
    private String password;

    /** Surrounding spaces are a common paste artefact, so they are dropped before validation. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}

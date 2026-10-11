package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Lengths are counted on the text as typed: a space is one more character. */
@Data
public class RegisterRequest {

    public static final int MIN_NAME_LENGTH = 2;
    public static final int MAX_NAME_LENGTH = 18;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 32;

    @NotBlank(message = "El nombre es obligatorio.")
    @Size(min = MIN_NAME_LENGTH, max = MAX_NAME_LENGTH, message = "El nombre debe tener entre 2 y 18 caracteres.")
    private String name;

    @NotBlank(message = "El apellido es obligatorio.")
    @Size(min = MIN_NAME_LENGTH, max = MAX_NAME_LENGTH, message = "El apellido debe tener entre 2 y 18 caracteres.")
    private String lastName;

    @NotBlank(message = "El correo es obligatorio.")
    @Email(message = "El correo no es válido.")
    @Size(min = 6, max = 160, message = "El correo debe tener entre 6 y 160 caracteres.")
    private String email;

    @NotNull(message = "La contraseña es obligatoria.")
    @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_LENGTH,
            message = "La contraseña debe tener entre 8 y 32 caracteres.")
    private String password;

    /** Surrounding spaces are a common paste artefact, so they are dropped before validation. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}

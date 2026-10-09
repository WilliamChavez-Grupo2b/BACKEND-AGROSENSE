package com.agrosense.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @Email(message = "Email wrong")
    @NotBlank(message = "Email wrong")
    private String email;

    @NotBlank(message = "Required Password")
    private String password;
}
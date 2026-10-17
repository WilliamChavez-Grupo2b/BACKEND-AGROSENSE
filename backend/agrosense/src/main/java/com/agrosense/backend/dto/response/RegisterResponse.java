package com.agrosense.backend.dto.response;

public record RegisterResponse(Integer idUser, String name, String lastName, String email, String role) {
}

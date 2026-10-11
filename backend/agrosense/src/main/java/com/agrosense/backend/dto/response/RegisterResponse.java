package com.agrosense.backend.dto.response;

/** The account that was just created. It carries no token: the user signs in next. */
public record RegisterResponse(Integer idUser, String name, String lastName, String email, String role) {
}

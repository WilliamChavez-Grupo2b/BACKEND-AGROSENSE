package com.agrosense.backend.dto.response;

public record UserCheckResponse(boolean exists, boolean available) {

    public static UserCheckResponse of(boolean exists) {
        return new UserCheckResponse(exists, !exists);
    }
}

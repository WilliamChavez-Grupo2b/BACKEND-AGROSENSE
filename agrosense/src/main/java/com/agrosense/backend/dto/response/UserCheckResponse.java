package com.agrosense.backend.dto.response;

/** Whether an account with the e-mail exists; {@code available} is its opposite, for sign-up forms. */
public record UserCheckResponse(boolean exists, boolean available) {

    public static UserCheckResponse of(boolean exists) {
        return new UserCheckResponse(exists, !exists);
    }
}

package com.agrosense.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Body of every error answer; the message is written for the end user. Validation errors also list the
 * message of each rejected field under {@code fields}.
 */
public record ErrorResponse(String message, @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fields) {

    public ErrorResponse(String message) {
        this(message, null);
    }
}

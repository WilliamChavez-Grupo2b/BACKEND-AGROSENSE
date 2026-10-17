package com.agrosense.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

public record ErrorResponse(String message, @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fields) {

    public ErrorResponse(String message) {
        this(message, null);
    }
}

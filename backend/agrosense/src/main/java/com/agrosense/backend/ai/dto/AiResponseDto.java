package com.agrosense.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AiResponseDto(String reply, @JsonInclude(JsonInclude.Include.NON_NULL) String transcript,
        String source) {

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_LOCAL = "local";

    public AiResponseDto heard(String spoken) {
        return new AiResponseDto(reply, spoken, source);
    }
}

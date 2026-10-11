package com.agrosense.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The assistant's answer. {@code transcript} is what it understood from a voice message, so the
 * conversation can show the question too; it is absent for text questions.
 */
public record AiResponseDto(String reply, @JsonInclude(JsonInclude.Include.NON_NULL) String transcript) {

    public AiResponseDto(String reply) {
        this(reply, null);
    }
}

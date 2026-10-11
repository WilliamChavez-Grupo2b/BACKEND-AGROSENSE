package com.agrosense.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The assistant's answer. {@code transcript} is what it understood from a voice message, so the
 * conversation can show the question too; it is absent for text questions. {@code source} says who
 * wrote the answer: the language model, or the assistant on its own from the farm's figures when the
 * model could not be reached.
 */
public record AiResponseDto(String reply, @JsonInclude(JsonInclude.Include.NON_NULL) String transcript,
        String source) {

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_LOCAL = "local";

    /** The same answer, with the text of the voice message it replies to. */
    public AiResponseDto heard(String spoken) {
        return new AiResponseDto(reply, spoken, source);
    }
}

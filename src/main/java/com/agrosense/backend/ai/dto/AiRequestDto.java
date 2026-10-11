package com.agrosense.backend.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** A question for the assistant and, optionally, the turns of the conversation that came before it. */
@Data
public class AiRequestDto {

    public static final int MAX_MESSAGE_LENGTH = 1000;
    /** Older turns are dropped by the client; more than this is refused so a request cannot grow without limit. */
    public static final int MAX_HISTORY_TURNS = 12;
    public static final int MAX_TURN_LENGTH = 4000;

    @NotBlank(message = "Escribe tu pregunta.")
    @Size(max = MAX_MESSAGE_LENGTH, message = "La pregunta no puede superar los 1000 caracteres.")
    private String message;

    @Size(max = MAX_HISTORY_TURNS, message = "La conversación enviada es demasiado larga.")
    private List<@Valid Turn> history = new ArrayList<>();

    @Data
    public static class Turn {

        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";

        @NotBlank(message = "Cada mensaje de la conversación necesita un autor.")
        @Pattern(regexp = USER + "|" + ASSISTANT, message = "El autor de un mensaje no es válido.")
        private String role;

        @NotBlank(message = "Los mensajes de la conversación no pueden estar vacíos.")
        @Size(max = MAX_TURN_LENGTH, message = "Un mensaje de la conversación es demasiado largo.")
        private String text;
    }
}

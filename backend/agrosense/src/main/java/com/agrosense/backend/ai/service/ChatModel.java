package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public interface ChatModel {

    record Said(String role, String text) {
    }

    String USER = "user";
    String ASSISTANT = "assistant";

    String chat(String instructions, List<Said> conversation);

    default boolean listens() {
        return false;
    }

    default String transcribe(byte[] audio, String mimeType) {
        throw new AiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ahora no puedo escuchar mensajes de voz. Escribe tu pregunta y te respondo.");
    }
}

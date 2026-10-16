package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * A language model that answers written questions through an HTTP API: the OpenAI-compatible providers
 * or Anthropic's Claude. Gemini, which also listens to recordings in the same call, is reached directly
 * by {@link AiAssistantService}.
 */
public interface ChatModel {

    /** One side's part of a conversation; {@code role} is {@link #USER} or {@link #ASSISTANT}. */
    record Said(String role, String text) {
    }

    String USER = "user";
    String ASSISTANT = "assistant";

    /**
     * The model's answer to the last turn of the conversation, under the given instructions. Failures
     * arrive as an {@link AiException}: 429 out of allowance, 502 broken, 504 too slow, 422 refused.
     */
    String chat(String instructions, List<Said> conversation);

    /** Whether recordings can be turned into text; a model without that still answers written questions. */
    default boolean listens() {
        return false;
    }

    /** What is said, in Spanish, in a recording of the given type; only called when {@link #listens()}. */
    default String transcribe(byte[] audio, String mimeType) {
        throw new AiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ahora no puedo escuchar mensajes de voz. Escribe tu pregunta y te respondo.");
    }
}

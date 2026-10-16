package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A language model reached through the API that OpenAI defined and many providers offer (Groq,
 * OpenRouter, Mistral, OpenAI itself...): {@code /chat/completions} for answers and
 * {@code /audio/transcriptions} to turn a recording into text. Which provider it is depends only on the
 * base address, the key and the model names, all of which come from the environment.
 */
@Slf4j
public class OpenAiCompatibleClient {

    /** One side's part of a conversation; {@code role} is {@code user} or {@code assistant}. */
    public record Said(String role, String text) {
    }

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    /** The file name tells the provider how to read the recording; the type alone is not enough for it. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/webm", "webm", "audio/ogg", "ogg", "audio/wav", "wav", "audio/x-wav", "wav",
            "audio/mp4", "mp4", "audio/mpeg", "mp3", "audio/mp3", "mp3", "audio/flac", "flac", "audio/aac", "aac");

    private final WebClient client;
    /** Tried in this order: when one is out of allowance or no longer exists, the next one answers. */
    private final List<String> models;
    private final String transcriptionModel;
    private final int maxOutputTokens;

    /**
     * @param models one model name, or several separated by commas. Providers limit each model on its
     *               own and retire names over time, so a second one keeps the assistant answering.
     */
    public OpenAiCompatibleClient(String apiKey, String baseUrl, String models, String transcriptionModel,
            int timeoutMs, int maxOutputTokens) {
        this.models = Arrays.stream(models.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
        if (this.models.isEmpty()) {
            throw new IllegalStateException("AI_CHAT_MODEL must name at least one model");
        }
        this.transcriptionModel = transcriptionModel.trim();
        this.maxOutputTokens = maxOutputTokens;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        this.client = WebClient.builder()
                // Without the trailing slash, so the paths below are appended to whatever the address ends with.
                .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** Whether recordings can be turned into text; a provider without that still answers written questions. */
    public boolean listens() {
        return !transcriptionModel.isEmpty();
    }

    /** The model's answer to the last turn of the conversation, under the given instructions. */
    public String chat(String instructions, List<Said> conversation) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", instructions));
        for (Said turn : conversation) {
            messages.add(Map.of("role", turn.role(), "content", turn.text()));
        }
        AiException lastFailure = null;
        for (String model : models) {
            try {
                return complete(model, messages);
            } catch (AiException failure) {
                // Out of allowance, gone, or broken for this model: another model may still answer. A
                // refusal is about the question, and a provider that does not respond will not respond
                // for the next model either; trying again would only make the user wait twice.
                if (failure.getStatus() != HttpStatus.TOO_MANY_REQUESTS && failure.getStatus() != HttpStatus.BAD_GATEWAY) {
                    throw failure;
                }
                lastFailure = failure;
            }
        }
        throw lastFailure;
    }

    private String complete(String model, List<Map<String, String>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.4);
        body.put("max_tokens", maxOutputTokens);
        JsonNode answer = send(() -> client.post().uri("/chat/completions").bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block(), model);
        JsonNode choice = answer.path("choices").path(0);
        String reply = choice.path("message").path("content").asString("").trim();
        if (!reply.isEmpty()) {
            return reply;
        }
        if ("content_filter".equals(choice.path("finish_reason").asString(""))) {
            throw new AiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No puedo responder a esa consulta. Prueba a formularla de otra manera.");
        }
        throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no generó una respuesta. Inténtalo de nuevo.");
    }

    /** What is said, in Spanish, in a recording of the given type (for example {@code audio/webm}). */
    public String transcribe(byte[] audio, String mimeType) {
        String fileName = "voice." + EXTENSIONS.getOrDefault(mimeType, "webm");
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.parseMediaType(mimeType));
        form.part("model", transcriptionModel);
        form.part("language", "es");
        form.part("response_format", "json");
        JsonNode answer = send(() -> client.post().uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .retrieve().bodyToMono(JsonNode.class).block(), transcriptionModel);
        String heard = answer.path("text").asString("").trim();
        if (heard.isEmpty()) {
            throw new AiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se entendió el mensaje de voz. Inténtalo de nuevo o escribe tu pregunta.");
        }
        return heard;
    }

    /** Runs a call and turns each way it can fail into an answer the user can read. */
    private static JsonNode send(java.util.function.Supplier<JsonNode> call, String model) {
        try {
            JsonNode answer = call.get();
            if (answer == null) {
                throw new AiException(HttpStatus.BAD_GATEWAY,
                        "El asistente no devolvió ninguna respuesta. Inténtalo de nuevo.");
            }
            return answer;
        } catch (WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            // The provider's own text may quote the key or the request; only its status is logged.
            log.warn("El proveedor de IA respondió {} al modelo {}", status, model);
            if (status == 429) {
                throw new AiException(HttpStatus.TOO_MANY_REQUESTS,
                        "El asistente está recibiendo muchas consultas. Inténtalo de nuevo en un minuto.");
            }
            throw new AiException(HttpStatus.BAD_GATEWAY,
                    "El asistente no está disponible en este momento. Inténtalo más tarde.");
        } catch (AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Timeouts and connection failures; the key travels in a header and is not part of the cause.
            log.warn("No se pudo contactar al proveedor de IA: {}", exception.getClass().getSimpleName());
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT,
                    "El asistente tardó demasiado en responder. Inténtalo de nuevo.");
        }
    }
}

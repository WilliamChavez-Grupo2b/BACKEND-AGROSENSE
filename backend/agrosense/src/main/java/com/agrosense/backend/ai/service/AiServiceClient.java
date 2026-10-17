package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The AI service (IA-AGROSENSE) answering the assistant: {@code POST /chat} and {@code POST /voice}. The
 * backend sends the question, the earlier turns and the description of the caller's own farm; the service
 * never reads the database. Every call carries {@code X-Internal-Key} ({@code AI_SERVICE_KEY}) and, while the
 * service still accepts it, the bearer token predictions already used ({@code AI_SERVICE_TOKEN}).
 */
@Slf4j
public class AiServiceClient {

    /** Statuses whose message the service writes for the end user and that keep their meaning here. */
    private static final Set<Integer> PASSED_ON = Set.of(400, 413, 415, 422, 429, 503, 504);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebClient client;

    public AiServiceClient(String baseUrl, String key, String token, int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (key != null && !key.isBlank()) {
            builder.defaultHeader("X-Internal-Key", key.trim());
        }
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        this.client = builder.build();
    }

    /** The answer to a written question. */
    public String chat(String farmContext, List<AiRequestDto.Turn> history, String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", question);
        body.put("history", history.stream().map(turn -> Map.of("role", turn.getRole(), "text", turn.getText())).toList());
        body.put("farm_context", farmContext);
        JsonNode answer = send(() -> client.post().uri("/chat").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block());
        return replyOf(answer);
    }

    /** The answer to a recording, with what the service understood from it. */
    public AiResponseDto voice(byte[] audio, String mimeType, String farmContext) {
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("audio", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "voice";
            }
        }).contentType(MediaType.parseMediaType(mimeType));
        form.part("farm_context", farmContext);
        JsonNode answer = send(() -> client.post().uri("/voice").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .retrieve().bodyToMono(JsonNode.class).block());
        String heard = answer.path("transcript").asString("").trim();
        return new AiResponseDto(replyOf(answer), heard.isEmpty() ? null : heard, AiResponseDto.SOURCE_MODEL);
    }

    private static String replyOf(JsonNode answer) {
        String reply = answer.path("reply").asString("").trim();
        if (reply.isEmpty()) {
            throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no generó una respuesta. Inténtalo de nuevo.");
        }
        return reply;
    }

    /**
     * Runs a call and turns each way it can fail into an {@link AiException}. The statuses the service
     * shares with the backend (out of allowance, no provider, too slow, refused) keep their meaning and
     * the service's own message; anything else means the service is not working as it should.
     */
    private static JsonNode send(Supplier<JsonNode> call) {
        try {
            JsonNode answer = call.get();
            if (answer == null) {
                throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no devolvió ninguna respuesta. Inténtalo de nuevo.");
            }
            return answer;
        } catch (WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            log.warn("The AI service answered {} to the assistant", status);
            String message = messageOf(exception);
            if (PASSED_ON.contains(status) && message != null) {
                throw new AiException(HttpStatus.valueOf(status), message);
            }
            throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no está disponible en este momento. Inténtalo más tarde.");
        } catch (AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Timeouts and connection failures; the key travels in a header and is not part of the cause.
            log.warn("Could not reach the AI service: {}", exception.getClass().getSimpleName());
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT, "El asistente tardó demasiado en responder. Inténtalo de nuevo.");
        }
    }

    private static String messageOf(WebClientResponseException exception) {
        try {
            String message = JSON.readTree(exception.getResponseBodyAsString()).path("message").asString("").trim();
            return message.isEmpty() ? null : message;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}

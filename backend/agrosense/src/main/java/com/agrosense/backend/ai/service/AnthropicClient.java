package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnthropicClient implements ChatModel {

    static final String API_VERSION = "2023-06-01";

    private final WebClient client;
    private final String model;
    private final String effort;
    private final int maxOutputTokens;

    public AnthropicClient(String apiKey, String baseUrl, String model, String effort, int timeoutMs,
            int maxOutputTokens) {
        this.model = model.trim();
        if (this.model.isEmpty()) {
            throw new IllegalStateException("ANTHROPIC_MODEL must name a model");
        }
        this.effort = effort == null ? "" : effort.trim();
        this.maxOutputTokens = maxOutputTokens;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        this.client = WebClient.builder()
                .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", API_VERSION)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public String chat(String instructions, List<Said> conversation) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Said turn : conversation) {
            messages.add(Map.of("role", turn.role(), "content", turn.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("system", instructions);
        body.put("messages", messages);
        if (!effort.isEmpty()) {
            body.put("output_config", Map.of("effort", effort));
        }
        JsonNode answer = OpenAiCompatibleClient.send(() -> client.post().uri("/v1/messages").bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block(), model);
        if ("refusal".equals(answer.path("stop_reason").asString(""))) {
            throw new AiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No puedo responder a esa consulta. Prueba a formularla de otra manera.");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : answer.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        String reply = text.toString().trim();
        if (reply.isEmpty()) {
            throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no generó una respuesta. Inténtalo de nuevo.");
        }
        return reply;
    }
}

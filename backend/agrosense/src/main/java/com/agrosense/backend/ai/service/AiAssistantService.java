package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.AiException;
import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
public class AiAssistantService {

    static final String SYSTEM_PROMPT_RESOURCE = "ai/prompts/system-es.txt";

    static final String SYSTEM_PROMPT = readResource(SYSTEM_PROMPT_RESOURCE);

    static final String VOICE_INSTRUCTION = "Transcribe el audio en español y responde a lo que pregunta.";

    public static final int MAX_AUDIO_BYTES = 3 * 1024 * 1024;

    public static final int MAX_HISTORY_SENT = 8;

    private static final Set<String> AUDIO_TYPES = Set.of("audio/webm", "audio/ogg", "audio/wav",
            "audio/x-wav", "audio/mp4", "audio/mpeg", "audio/mp3", "audio/aac", "audio/flac");

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<HttpStatus> MODEL_UNAVAILABLE = Set.of(HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT);

    private static final Set<HttpStatus> SERVICE_UNAVAILABLE = Set.of(HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT);

    private final FarmContextService farmContext;
    private final AiServiceClient service;
    private final boolean localAnswers;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ChatModel chatModel;
    private final WebClient gemini;
    private final String apiKey;
    private final String model;
    private final int maxOutputTokens;
    private final int requestsPerMinute;
    private final Map<String, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

    @Autowired
    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.model:gemini-flash-latest}") String model,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${gemini.timeout-ms:20000}") int timeoutMs,
            @Value("${gemini.max-output-tokens:2048}") int maxOutputTokens,
            @Value("${ai.assistant.requests-per-minute:10}") int requestsPerMinute,
            @Value("${ai.assistant.local-answers:true}") boolean localAnswers,
            @Value("${ai.chat.api-key:}") String chatApiKey,
            @Value("${ai.chat.base-url:https://api.groq.com/openai/v1}") String chatBaseUrl,
            @Value("${ai.chat.model:qwen/qwen3.8-27b}") String chatModels,
            @Value("${ai.chat.transcription-model:whisper-large-v3-turbo}") String transcriptionModel,
            @Value("${anthropic.api-key:}") String anthropicApiKey,
            @Value("${anthropic.base-url:https://api.anthropic.com}") String anthropicBaseUrl,
            @Value("${anthropic.model:claude-haiku-5-5}") String anthropicModel,
            @Value("${anthropic.effort:low}") String anthropicEffort,
            @Value("${ai.chat.via-service:false}") boolean viaService,
            @Value("${ai.service.url:}") String serviceUrl,
            @Value("${ai.service.key:}") String serviceKey,
            @Value("${ai.service.token:}") String serviceToken,
            @Value("${ai.service.timeout-ms:30000}") int serviceTimeoutMs) {
        this(farmContext, objectMapper, Clock.systemUTC(), apiKey, model, baseUrl, timeoutMs, maxOutputTokens,
                requestsPerMinute, localAnswers,
                chosenModel(chatApiKey, chatBaseUrl, chatModels, transcriptionModel, anthropicApiKey,
                        anthropicBaseUrl, anthropicModel, anthropicEffort, timeoutMs, maxOutputTokens),
                chosenService(viaService, serviceUrl, serviceKey, serviceToken, serviceTimeoutMs));
    }

    public static AiServiceClient chosenService(boolean viaService, String serviceUrl, String serviceKey,
            String serviceToken, int timeoutMs) {
        if (!viaService) {
            return null;
        }
        if (serviceUrl.isBlank()) {
            log.warn("AI_CHAT_VIA_SERVICE is on but AI_SERVICE_URL is empty: the assistant uses its built-in AI");
            return null;
        }
        log.info("The assistant answers through the AI service at {}", serviceUrl.trim());
        return new AiServiceClient(serviceUrl, serviceKey, serviceToken, timeoutMs);
    }

    public static ChatModel chosenModel(String chatApiKey, String chatBaseUrl, String chatModels, String transcriptionModel,
            String anthropicApiKey, String anthropicBaseUrl, String anthropicModel, String anthropicEffort,
            int timeoutMs, int maxOutputTokens) {
        if (!chatApiKey.isBlank()) {
            return new OpenAiCompatibleClient(chatApiKey, chatBaseUrl, chatModels, transcriptionModel, timeoutMs,
                    maxOutputTokens);
        }
        if (!anthropicApiKey.isBlank()) {
            return new AnthropicClient(anthropicApiKey, anthropicBaseUrl, anthropicModel, anthropicEffort, timeoutMs,
                    maxOutputTokens);
        }
        return null;
    }

    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper, Clock clock,
            String apiKey, String model, String baseUrl, int timeoutMs, int maxOutputTokens,
            int requestsPerMinute, boolean localAnswers) {
        this(farmContext, objectMapper, clock, apiKey, model, baseUrl, timeoutMs, maxOutputTokens,
                requestsPerMinute, localAnswers, null);
    }

    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper, Clock clock,
            String apiKey, String model, String baseUrl, int timeoutMs, int maxOutputTokens,
            int requestsPerMinute, boolean localAnswers, ChatModel chatModel) {
        this(farmContext, objectMapper, clock, apiKey, model, baseUrl, timeoutMs, maxOutputTokens,
                requestsPerMinute, localAnswers, chatModel, null);
    }

    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper, Clock clock,
            String apiKey, String model, String baseUrl, int timeoutMs, int maxOutputTokens,
            int requestsPerMinute, boolean localAnswers, ChatModel chatModel, AiServiceClient service) {
        this.service = service;
        this.chatModel = chatModel;
        this.farmContext = farmContext;
        this.localAnswers = localAnswers;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        this.maxOutputTokens = maxOutputTokens;
        this.requestsPerMinute = requestsPerMinute;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        this.gemini = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public AiResponseDto chat(String email, AiRequestDto request) {
        countRequest(email);
        return answer(email, request.getMessage().trim(), latest(request.getHistory()));
    }

    static List<AiRequestDto.Turn> latest(List<AiRequestDto.Turn> history) {
        if (history == null) {
            return List.of();
        }
        return history.subList(Math.max(0, history.size() - MAX_HISTORY_SENT), history.size());
    }

    private AiResponseDto answer(String email, String question, List<AiRequestDto.Turn> history) {
        FarmSnapshot farm = farmOf(email);
        if (service != null) {
            try {
                return new AiResponseDto(service.chat(describe(farm), history, question), null,
                        AiResponseDto.SOURCE_MODEL);
            } catch (AiException failure) {
                if (!SERVICE_UNAVAILABLE.contains(failure.getStatus())) {
                    throw failure;
                }
                return withoutTheModel(question, farm, failure);
            }
        }
        if (chatModel == null && apiKey.isEmpty()) {
            return withoutTheModel(question, farm, notConfigured());
        }
        List<ChatModel.Said> conversation = conversation(history, question);
        try {
            String reply = chatModel != null
                    ? chatModel.chat(SYSTEM_PROMPT + "\n\n" + describe(farm), conversation)
                    : textOf(generate(farm, conversation.stream()
                            .map(turn -> content(ChatModel.USER.equals(turn.role()) ? "user" : "model",
                                    Map.of("text", turn.text())))
                            .toList(), false));
            return new AiResponseDto(reply, null, AiResponseDto.SOURCE_MODEL);
        } catch (AiException failure) {
            if (!MODEL_UNAVAILABLE.contains(failure.getStatus())) {
                throw failure;
            }
            return withoutTheModel(question, farm, failure);
        }
    }

    private AiResponseDto withoutTheModel(String question, FarmSnapshot farm, AiException reason) {
        if (!localAnswers) {
            throw unavailable(reason);
        }
        return new AiResponseDto(LocalAdvisor.answer(question, farm), null, AiResponseDto.SOURCE_LOCAL);
    }

    private static AiException unavailable(AiException reason) {
        return reason.getStatus() == HttpStatus.SERVICE_UNAVAILABLE ? reason
                : new AiException(HttpStatus.SERVICE_UNAVAILABLE, reason.getMessage());
    }

    private static AiException notConfigured() {
        return new AiException(HttpStatus.SERVICE_UNAVAILABLE, "El asistente de IA no está configurado todavía.");
    }

    private FarmSnapshot farmOf(String email) {
        try {
            return farmContext.snapshot(email);
        } catch (RuntimeException exception) {
            log.warn("Could not read the farm data for the assistant: {}", exception.getMessage());
            return null;
        }
    }

    private String describe(FarmSnapshot farm) {
        String now = "Fecha y hora actual: "
                + LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).format(NOW) + ".\n";
        return now + (farm == null ? "No hay datos de la finca disponibles en este momento." : farm.describe());
    }

    private static List<ChatModel.Said> conversation(List<AiRequestDto.Turn> history, String question) {
        List<String> roles = new ArrayList<>();
        List<StringBuilder> texts = new ArrayList<>();
        for (AiRequestDto.Turn turn : history) {
            add(roles, texts, AiRequestDto.Turn.ASSISTANT.equals(turn.getRole())
                    ? ChatModel.ASSISTANT : ChatModel.USER, turn.getText());
        }
        add(roles, texts, ChatModel.USER, question);
        List<ChatModel.Said> conversation = new ArrayList<>();
        for (int index = 0; index < roles.size(); index++) {
            conversation.add(new ChatModel.Said(roles.get(index), texts.get(index).toString()));
        }
        return conversation;
    }

    private static void add(List<String> roles, List<StringBuilder> texts, String role, String text) {
        if (roles.isEmpty() && ChatModel.ASSISTANT.equals(role)) {
            return;
        }
        if (!roles.isEmpty() && roles.get(roles.size() - 1).equals(role)) {
            texts.get(texts.size() - 1).append('\n').append(text);
            return;
        }
        roles.add(role);
        texts.add(new StringBuilder(text));
    }

    public AiResponseDto voice(String email, byte[] audio, String contentType, String transcript) {
        String spoken = transcript == null ? "" : transcript.trim();
        if (!spoken.isEmpty()) {
            if (spoken.length() > AiRequestDto.MAX_MESSAGE_LENGTH) {
                throw new AiException(HttpStatus.BAD_REQUEST, "El mensaje de voz es demasiado largo.");
            }
            AiRequestDto request = new AiRequestDto();
            request.setMessage(spoken);
            return chat(email, request).heard(spoken);
        }
        if (audio == null || audio.length == 0) {
            throw new AiException(HttpStatus.BAD_REQUEST, "No se recibió ningún audio.");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new AiException(HttpStatus.PAYLOAD_TOO_LARGE, "El audio es demasiado largo. Graba un mensaje más corto.");
        }
        String mimeType = baseTypeOf(contentType);
        if (!AUDIO_TYPES.contains(mimeType)) {
            throw new AiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ese formato de audio no es compatible.");
        }
        if (service != null) {
            countRequest(email);
            String farm = describe(farmOf(email));
            return listening(() -> service.voice(audio, mimeType, farm));
        }
        if (chatModel != null && chatModel.listens()) {
            countRequest(email);
            String heard = listening(() -> chatModel.transcribe(audio, mimeType));
            if (heard.length() > AiRequestDto.MAX_MESSAGE_LENGTH) {
                heard = heard.substring(0, AiRequestDto.MAX_MESSAGE_LENGTH);
            }
            return answer(email, heard, List.of()).heard(heard);
        }
        if (chatModel != null || apiKey.isEmpty()) {
            throw localAnswers
                    ? new AiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Ahora no puedo escuchar mensajes de voz. Escribe tu pregunta y te respondo.")
                    : notConfigured();
        }
        countRequest(email);
        Map<String, Object> audioPart = Map.of("inline_data",
                Map.of("mime_type", mimeType, "data", Base64.getEncoder().encodeToString(audio)));
        FarmSnapshot farm = farmOf(email);
        String answer = listening(() -> textOf(generate(farm,
                List.of(content("user", audioPart, Map.of("text", VOICE_INSTRUCTION))), true)));
        try {
            JsonNode parsed = objectMapper.readTree(answer);
            String reply = parsed.path("reply").asString("").trim();
            if (!reply.isEmpty()) {
                String heard = parsed.path("transcript").asString("").trim();
                return new AiResponseDto(reply, heard.isEmpty() ? null : heard, AiResponseDto.SOURCE_MODEL);
            }
        } catch (JacksonException exception) {
            log.warn("The assistant did not return the requested JSON for a voice message");
        }
        return new AiResponseDto(answer, null, AiResponseDto.SOURCE_MODEL);
    }

    private static <T> T listening(Supplier<T> call) {
        try {
            return call.get();
        } catch (AiException failure) {
            throw MODEL_UNAVAILABLE.contains(failure.getStatus()) ? unavailable(failure) : failure;
        }
    }

    private JsonNode generate(FarmSnapshot farm, List<Map<String, Object>> contents, boolean voiceAnswer) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.4);
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        if (voiceAnswer) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", Map.of(
                    "type", "OBJECT",
                    "properties", Map.of(
                            "transcript", Map.of("type", "STRING"),
                            "reply", Map.of("type", "STRING")),
                    "required", List.of("transcript", "reply")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(
                Map.of("text", SYSTEM_PROMPT),
                Map.of("text", describe(farm)))));
        body.put("contents", contents);
        body.put("generationConfig", generationConfig);
        try {
            JsonNode answer = gemini.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (answer == null) {
                throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no devolvió ninguna respuesta. Inténtalo de nuevo.");
            }
            return answer;
        } catch (WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            log.warn("Gemini answered {} to model {}", status, model);
            if (status == 429) {
                throw new AiException(HttpStatus.TOO_MANY_REQUESTS,
                        "El asistente está recibiendo muchas consultas. Inténtalo de nuevo en un minuto.");
            }
            throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no está disponible en este momento. Inténtalo más tarde.");
        } catch (AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Could not reach Gemini: {}", exception.getClass().getSimpleName());
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT, "El asistente tardó demasiado en responder. Inténtalo de nuevo.");
        }
    }

    private static String textOf(JsonNode answer) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : answer.path("candidates").path(0).path("content").path("parts")) {
            text.append(part.path("text").asString(""));
        }
        String reply = text.toString().trim();
        if (!reply.isEmpty()) {
            return reply;
        }
        if (!answer.path("promptFeedback").path("blockReason").isMissingNode()) {
            throw new AiException(HttpStatus.UNPROCESSABLE_ENTITY, "No puedo responder a esa consulta. Prueba a formularla de otra manera.");
        }
        throw new AiException(HttpStatus.BAD_GATEWAY, "El asistente no generó una respuesta. Inténtalo de nuevo.");
    }

    private void countRequest(String email) {
        if (requestsPerMinute <= 0) {
            return;
        }
        Instant now = clock.instant();
        boolean[] refused = {false};
        recentRequests.compute(email, (key, known) -> {
            Deque<Instant> requests = known == null ? new ArrayDeque<>() : known;
            dropOlderThanTheWindow(requests, now);
            if (requests.size() >= requestsPerMinute) {
                refused[0] = true;
            } else {
                requests.addLast(now);
            }
            return requests;
        });
        if (refused[0]) {
            throw new AiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Has hecho muchas consultas seguidas. Espera un minuto antes de continuar.");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void forgetIdleUsers() {
        Instant now = clock.instant();
        for (String email : recentRequests.keySet()) {
            recentRequests.computeIfPresent(email, (key, requests) -> {
                dropOlderThanTheWindow(requests, now);
                return requests.isEmpty() ? null : requests;
            });
        }
    }

    public int trackedUsers() {
        return recentRequests.size();
    }

    private static void dropOlderThanTheWindow(Deque<Instant> requests, Instant now) {
        while (!requests.isEmpty() && !requests.peekFirst().isAfter(now.minus(RATE_WINDOW))) {
            requests.pollFirst();
        }
    }

    @SafeVarargs
    private static Map<String, Object> content(String role, Map<String, Object>... parts) {
        return Map.of("role", role, "parts", List.of(parts));
    }

    private static String baseTypeOf(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static String readResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not find " + path, exception);
        }
    }
}

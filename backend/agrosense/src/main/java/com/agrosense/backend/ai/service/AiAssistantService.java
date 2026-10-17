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

/**
 * The conversational assistant. Each question travels with the system prompt and a compact summary of
 * the caller's own farm, so answers refer to real figures while the request stays small. With
 * {@code AI_CHAT_VIA_SERVICE=true} it is answered by the AI service (see {@link #chosenService}); otherwise by
 * the first provider with a key: OpenAI-compatible, Claude, Gemini (see {@link #chosenModel}). Those
 * providers are the older path, kept for a backend without the service. Whenever no model answers, the
 * question is answered from the farm's own figures.
 */
@Slf4j
@Service
public class AiAssistantService {

    /** Kept as a text file so it can be reworded without touching the code. */
    static final String SYSTEM_PROMPT_RESOURCE = "ai/prompts/system-es.txt";

    static final String SYSTEM_PROMPT = readResource(SYSTEM_PROMPT_RESOURCE);

    /** Asked of a voice message: what was said, so the conversation can show it, and the answer to it. */
    static final String VOICE_INSTRUCTION = "Transcribe el audio en español y responde a lo que pregunta.";

    public static final int MAX_AUDIO_BYTES = 3 * 1024 * 1024;

    /** Earlier turns sent to the model with a question; the request may carry more, the oldest are left out. */
    public static final int MAX_HISTORY_SENT = 8;

    /** What browsers record (WebM, Ogg, MP4) and the formats Gemini documents for audio. */
    private static final Set<String> AUDIO_TYPES = Set.of("audio/webm", "audio/ogg", "audio/wav",
            "audio/x-wav", "audio/mp4", "audio/mpeg", "audio/mp3", "audio/aac", "audio/flac");

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Failures of the model that say nothing about the question: it can still be answered without it. */
    private static final Set<HttpStatus> MODEL_UNAVAILABLE = Set.of(HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT);

    /**
     * What the AI service answers with when it cannot answer: the question can still be answered from the
     * farm's figures. Its 503 means it has no provider configured.
     */
    private static final Set<HttpStatus> SERVICE_UNAVAILABLE = Set.of(HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT);

    private final FarmContextService farmContext;
    /** The AI service (IA-AGROSENSE), when the assistant goes through it; {@code null} for the providers below. */
    private final AiServiceClient service;
    private final boolean localAnswers;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** An OpenAI-compatible provider or Claude, when one is configured; it is then used instead of Gemini. */
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

    /**
     * The AI service, when the assistant is to go through it: only with {@code AI_CHAT_VIA_SERVICE=true} and
     * {@code AI_SERVICE_URL} set, so turning predictions on does not move the assistant to another provider.
     */
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

    /**
     * The provider that answers, by priority: one that speaks the OpenAI API ({@code AI_CHAT_API_KEY}),
     * then Claude ({@code ANTHROPIC_API_KEY}); {@code null} leaves it to Gemini ({@code GEMINI_API_KEY})
     * and, without that key either, to the farm's own figures.
     */
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

    /** Answered by Gemini, or by nobody when {@code apiKey} is empty. */
    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper, Clock clock,
            String apiKey, String model, String baseUrl, int timeoutMs, int maxOutputTokens,
            int requestsPerMinute, boolean localAnswers) {
        this(farmContext, objectMapper, clock, apiKey, model, baseUrl, timeoutMs, maxOutputTokens,
                requestsPerMinute, localAnswers, null);
    }

    /**
     * With the clock given, so the limit on requests per minute can be tested without waiting.
     *
     * @param localAnswers whether a written question is answered from the farm's figures when the model
     *                     is not configured or does not respond, instead of being refused
     * @param chatModel    an OpenAI-compatible provider or Claude, used instead of Gemini; {@code null} for none
     */
    public AiAssistantService(FarmContextService farmContext, ObjectMapper objectMapper, Clock clock,
            String apiKey, String model, String baseUrl, int timeoutMs, int maxOutputTokens,
            int requestsPerMinute, boolean localAnswers, ChatModel chatModel) {
        this(farmContext, objectMapper, clock, apiKey, model, baseUrl, timeoutMs, maxOutputTokens,
                requestsPerMinute, localAnswers, chatModel, null);
    }

    /**
     * @param service the AI service; when given it answers every question and the providers above are not
     *                used. They stay as the assistant's own answer for a backend without that service.
     */
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

    /**
     * Answers a written question, taking the earlier turns of the conversation into account. When the
     * model is not configured or fails for reasons of its own, the question is answered from the farm's
     * figures instead, and the answer says so in its {@code source}.
     */
    public AiResponseDto chat(String email, AiRequestDto request) {
        countRequest(email);
        return answer(email, request.getMessage().trim(), latest(request.getHistory()));
    }

    /** The last {@link #MAX_HISTORY_SENT} turns: older ones cost tokens and rarely change the answer. */
    static List<AiRequestDto.Turn> latest(List<AiRequestDto.Turn> history) {
        if (history == null) {
            return List.of();
        }
        return history.subList(Math.max(0, history.size() - MAX_HISTORY_SENT), history.size());
    }

    /** Answers a question that has already been counted against the user's allowance. */
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
                            // Gemini calls the assistant's side "model".
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

    /**
     * A model that is down, slow or out of allowance is a 503 with its own explanation: a 429 is kept for
     * the user's own limit of questions per minute, which waiting a minute does lift.
     */
    private static AiException unavailable(AiException reason) {
        return reason.getStatus() == HttpStatus.SERVICE_UNAVAILABLE ? reason
                : new AiException(HttpStatus.SERVICE_UNAVAILABLE, reason.getMessage());
    }

    private static AiException notConfigured() {
        return new AiException(HttpStatus.SERVICE_UNAVAILABLE, "El asistente de IA no está configurado todavía.");
    }

    /** The caller's farm, or {@code null} when it cannot be read: the question is still worth answering. */
    private FarmSnapshot farmOf(String email) {
        try {
            return farmContext.snapshot(email);
        } catch (RuntimeException exception) {
            log.warn("Could not read the farm data for the assistant: {}", exception.getMessage());
            return null;
        }
    }

    /** The farm, after the current date and time: without them "hoy" and "ayer" mean nothing to the model. */
    private String describe(FarmSnapshot farm) {
        // Stored times are the server's local time, so "now" is said in the same terms.
        String now = "Fecha y hora actual: "
                + LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).format(NOW) + ".\n";
        return now + (farm == null ? "No hay datos de la finca disponibles en este momento." : farm.describe());
    }

    /**
     * The turns in the shape models accept: the first one is the user's and the two sides alternate. A
     * conversation kept by a browser does not always look like that (a question left without an answer,
     * or older turns dropped from the start), so answers with nothing before them are left out and turns
     * in a row from the same side become one.
     */
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

    /**
     * Answers a spoken question. The browser sends either what it recorded or, when it could transcribe
     * the speech itself, the text; with both, the text is used and the audio is not sent anywhere.
     */
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
            // The service transcribes and answers; a recording has no answer without a model to listen.
            countRequest(email);
            String farm = describe(farmOf(email));
            return listening(() -> service.voice(audio, mimeType, farm));
        }
        if (chatModel != null && chatModel.listens()) {
            // This kind of provider listens and answers in two steps; once the recording is text, it is a
            // written question like any other, also for the answers given without the model.
            countRequest(email);
            String heard = listening(() -> chatModel.transcribe(audio, mimeType));
            if (heard.length() > AiRequestDto.MAX_MESSAGE_LENGTH) {
                heard = heard.substring(0, AiRequestDto.MAX_MESSAGE_LENGTH);
            }
            return answer(email, heard, List.of()).heard(heard);
        }
        // Only a model can listen: without one a recording cannot be turned into a question.
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
        // The model ignored the requested shape: its text is still an answer worth showing.
        return new AiResponseDto(answer, null, AiResponseDto.SOURCE_MODEL);
    }

    /**
     * A recording only a model can turn into words, so when it is unavailable there is no answer to fall
     * back on: that is a 503, whether or not written questions are answered without the model.
     */
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
                    // In a header, not in the address, so the key never reaches a log of requested URLs.
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
            // Timeouts and connection failures; the cause never carries the key, which travels in a header.
            log.warn("Could not reach Gemini: {}", exception.getClass().getSimpleName());
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT, "El asistente tardó demasiado en responder. Inténtalo de nuevo.");
        }
    }

    /** The text of the first candidate; a blocked or empty answer becomes an error the user can read. */
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

    /** Counts one more question of this user and refuses it when the minute's allowance is spent. */
    private void countRequest(String email) {
        if (requestsPerMinute <= 0) {
            return;
        }
        Instant now = clock.instant();
        boolean[] refused = {false};
        // compute runs once at a time per user, so two questions sent together are both counted.
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

    /** Forgets the users who asked nothing in the last minute, so the map does not grow with every user. */
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

    /** How many users the limit is keeping count of. */
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

    /** {@code audio/webm;codecs=opus} is {@code audio/webm}. */
    private static String baseTypeOf(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    /** A text file packaged with the application; a missing one stops it from starting rather than answering blind. */
    static String readResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not find " + path, exception);
        }
    }
}

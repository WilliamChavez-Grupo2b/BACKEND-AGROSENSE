package com.agrosense.backend.ai.controller;

import com.agrosense.backend.ai.dto.AiRequestDto;
import com.agrosense.backend.ai.dto.AiResponseDto;
import com.agrosense.backend.ai.service.AiAssistantService;
import com.agrosense.backend.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;

/** The conversational assistant: written questions and spoken ones. */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class AiController {

    private final AiAssistantService assistant;

    @PostMapping("/chat")
    public AiResponseDto chat(@Valid @RequestBody AiRequestDto request, Principal principal) {
        return assistant.chat(principal.getName(), request);
    }

    /** Multipart form with {@code audio} (the recording), {@code transcript} (its text), or both. */
    @PostMapping(value = "/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiResponseDto voice(@RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "transcript", required = false) String transcript, Principal principal)
            throws IOException {
        return assistant.voice(principal.getName(),
                audio == null ? null : audio.getBytes(),
                audio == null ? null : audio.getContentType(),
                transcript);
    }
}

package com.agrosense.backend.controller;

import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.security.EventStreamTokens;
import com.agrosense.backend.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/** The token a signed-in user's browser presents to the event service to receive that user's live events. */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class EventStreamController {

    private final EventStreamTokens tokens;

    @GetMapping("/token")
    public ResponseEntity<Map<String, Object>> token(Principal principal) {
        String token = tokens.issue(principal.getName()).orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Los eventos en vivo no están configurados."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("token", token, "expiresInSeconds", EventStreamTokens.TIME_TO_LIVE.toSeconds()));
    }
}

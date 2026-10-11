package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.PredictionResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.PrediccionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crops/{cropId}/predictions")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class PrediccionController {

    private final PrediccionService prediccionService;

    @GetMapping
    public List<PredictionResponse> history(@PathVariable Integer cropId,
            @RequestParam(defaultValue = "20") int limit, Principal principal) {
        return prediccionService.findHistory(principal.getName(), cropId, limit);
    }

    /**
     * Starts an irrigation prediction from the crop's latest readings and answers at once: the result
     * shows up in the history when the AI service replies.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Roles.MANAGER)
    public void request(@PathVariable Integer cropId, Principal principal) {
        // Ownership is checked here, on the request thread, so a foreign crop gets a 404.
        Map<String, Object> conditions = prediccionService.currentConditions(principal.getName(), cropId);
        prediccionService.requestIrrigationPrediction(cropId, conditions);
    }
}

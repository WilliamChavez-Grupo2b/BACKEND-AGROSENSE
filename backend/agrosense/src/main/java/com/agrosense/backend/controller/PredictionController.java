package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.PredictionResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.PredictionService;
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
public class PredictionController {

    private final PredictionService predictionService;

    @GetMapping
    public List<PredictionResponse> history(@PathVariable Integer cropId,
            @RequestParam(defaultValue = "20") int limit, Principal principal) {
        return predictionService.findHistory(principal.getName(), cropId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Roles.MANAGER)
    public void request(@PathVariable Integer cropId, Principal principal) {
        Map<String, Object> conditions = predictionService.currentConditions(principal.getName(), cropId);
        predictionService.requestIrrigationPrediction(cropId, conditions);
    }
}

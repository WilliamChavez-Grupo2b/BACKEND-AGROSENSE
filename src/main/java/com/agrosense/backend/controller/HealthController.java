package com.agrosense.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The two public answers that say the API process is up and serving requests. */
@RestController
public class HealthController {

    private static final String CONTRACT = "https://github.com/ssantivr/API-AGROSENSE";

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * The address of the service on its own, which is what a person opens in a browser and what some
     * hosts check by default: it says what this is and where to look, instead of asking for a token.
     */
    @GetMapping("/")
    public Map<String, String> index() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("service", "AgroSense API");
        info.put("status", "UP");
        info.put("health", "/api/health");
        info.put("contract", CONTRACT);
        return info;
    }
}

package com.agrosense.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final String CONTRACT = "https://github.com/ssantivr/API-AGROSENSE";

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

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

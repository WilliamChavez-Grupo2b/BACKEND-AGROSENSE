package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public List<AlertResponse> open(@RequestParam(defaultValue = "50") int limit, Principal principal) {
        return alertService.findOpen(principal.getName(), limit);
    }

    @PatchMapping("/{alertId}/acknowledge")
    public AlertResponse acknowledge(@PathVariable Integer alertId, Principal principal) {
        return alertService.acknowledge(principal.getName(), alertId);
    }
}

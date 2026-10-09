package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class AlertaController {

    private final AlertService alertService;

    @GetMapping("/alerts")
    public List<AlertResponse> open(@RequestParam(defaultValue = "50") int limit, Principal principal) {
        return alertService.findOpen(principal.getName(), limit);
    }

    @GetMapping("/crops/{cropId}/alerts")
    public List<AlertResponse> openByCrop(@PathVariable Integer cropId,
            @RequestParam(defaultValue = "50") int limit, Principal principal) {
        return alertService.findOpenByCrop(principal.getName(), cropId, limit);
    }

    @PatchMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize(Roles.MANAGER)
    public AlertResponse acknowledge(@PathVariable Integer alertId, Principal principal) {
        return alertService.acknowledge(principal.getName(), alertId);
    }
}

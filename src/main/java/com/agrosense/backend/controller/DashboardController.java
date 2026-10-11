package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public DashboardResponse dashboard(Principal principal) {
        return dashboardService.build(principal.getName());
    }

    @GetMapping("/{cropId}")
    public DashboardResponse cropDashboard(@PathVariable Integer cropId, Principal principal) {
        return dashboardService.buildForCrop(principal.getName(), cropId);
    }
}

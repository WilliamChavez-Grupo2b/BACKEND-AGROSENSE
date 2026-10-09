package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public DashboardResponse dashboard(Principal principal) {
        return dashboardService.build(principal.getName());
    }
}

package com.agrosense.frontend.controller;

import com.agrosense.frontend.backend.BackendClient;
import com.agrosense.frontend.dto.MonitoringView;
import com.agrosense.frontend.service.SensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.time.ZoneId;

/** «Monitoreo»: what the backend does with the ESP32 while nobody looks, read from GET /api/devices/monitoring. */
@Controller
@RequiredArgsConstructor
public class MonitoringController {

    private final BackendClient backendClient;
    private final SensorService sensorService;

    /** The same device the backend reads over TCP (ESP32_DEVICE_ID). */
    @Value("${agrosense.esp32.device-id:ESP32-PLANTICAS}")
    private String esp32DeviceId;

    @GetMapping("/monitoreo")
    public String monitoring(Principal principal, Model model) {
        model.addAttribute("monitoring", backendClient.monitoring(principal.getName())
                .map(response -> MonitoringView.of(response, ZoneId.systemDefault()))
                .orElse(null));
        // Read from the database, so the route of the soil shows even while the backend is away.
        model.addAttribute("soilTrail", sensorService.findSoilTrail(principal.getName(), esp32DeviceId).orElse(null));
        return "monitoring";
    }
}

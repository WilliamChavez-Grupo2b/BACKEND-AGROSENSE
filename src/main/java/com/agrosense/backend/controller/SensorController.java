package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.SensorReadingRequest;
import com.agrosense.backend.dto.request.SensorRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.dto.response.SensorResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.SensorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class SensorController {

    private final SensorService sensorService;

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public SensorReadingResponse record(@Valid @RequestBody SensorReadingRequest request, Principal principal) {
        return sensorService.record(principal.getName(), request);
    }

    @GetMapping("/sensors/{sensorId}/readings")
    public List<SensorReadingResponse> latest(@PathVariable Integer sensorId,
            @RequestParam(defaultValue = "50") int limit, Principal principal) {
        return sensorService.findLatest(principal.getName(), sensorId, limit);
    }

    @GetMapping("/crops/{cropId}/sensors")
    public List<SensorResponse> byCrop(@PathVariable Integer cropId, Principal principal) {
        return sensorService.findByCrop(principal.getName(), cropId);
    }

    @PostMapping("/sensors")
    @ResponseStatus(HttpStatus.CREATED)
    public SensorResponse create(@Valid @RequestBody SensorRequest request, Principal principal) {
        return sensorService.create(principal.getName(), request.getCropId(), request.getSensorType(),
                request.getSensorCode(), request.getLocation());
    }
}

package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.SensorReadingRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.service.SensorReadingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class SensorReadingController {

    private final SensorReadingService readingService;

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public SensorReadingResponse record(@Valid @RequestBody SensorReadingRequest request, Principal principal) {
        return readingService.record(principal.getName(), request);
    }

    @GetMapping("/sensors/{sensorId}/readings")
    public List<SensorReadingResponse> latest(@PathVariable Integer sensorId,
            @RequestParam(defaultValue = "50") int limit, Principal principal) {
        return readingService.findLatest(principal.getName(), sensorId, limit);
    }
}

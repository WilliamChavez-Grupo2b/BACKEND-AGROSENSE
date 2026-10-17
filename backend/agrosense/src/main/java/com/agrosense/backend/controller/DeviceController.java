package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.DeviceRequest;
import com.agrosense.backend.dto.response.DeviceResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    @PreAuthorize(Roles.MANAGER)
    public ResponseEntity<DeviceResponse> create(@Valid @RequestBody DeviceRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(deviceService.create(principal.getName(), request.getCropId(), request.getDeviceCode()));
    }
}

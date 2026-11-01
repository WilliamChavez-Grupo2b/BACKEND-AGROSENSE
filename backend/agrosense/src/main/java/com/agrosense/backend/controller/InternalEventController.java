package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalEventController {

    public static final String DEVICE_KEY_HEADER = "X-Device-Key";

    private final DeviceIngestionFacade ingestion;

    @PostMapping("/events")
    public Map<String, Object> receive(@RequestHeader(value = DEVICE_KEY_HEADER, required = false) String deviceKey,
            @RequestBody DeviceEventRequest request) {
        return ingestion.ingest(deviceKey, request);
    }
}

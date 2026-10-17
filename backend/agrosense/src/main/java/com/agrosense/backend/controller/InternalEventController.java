package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.service.DeviceEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Routes only the event service calls. They are outside the users' token authentication: the event
 * service is let in by {@code X-Internal-Key} (see InternalKeyFilter), and each event by its device's key.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalEventController {

    public static final String DEVICE_KEY_HEADER = "X-Device-Key";

    private final DeviceEventService events;

    /** Stores the event and answers {@code {ownerEmail, event}}: who to deliver it to, and what to deliver. */
    @PostMapping("/events")
    public Map<String, Object> receive(@RequestHeader(value = DEVICE_KEY_HEADER, required = false) String deviceKey,
            @RequestBody DeviceEventRequest request) {
        return events.receive(request.getDeviceId(), deviceKey, request.getType(), request.getTs(), request.getData());
    }
}

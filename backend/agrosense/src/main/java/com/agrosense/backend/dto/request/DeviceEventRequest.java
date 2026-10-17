package com.agrosense.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * An event from a field device, as the event service forwards it: {@code {device_id, type, ts, data}}.
 * {@code ts} and {@code data} are checked by the service that stores the event, which names the field to fix.
 */
@Data
public class DeviceEventRequest {

    @JsonProperty("device_id")
    private String deviceId;

    private String type;

    private JsonNode ts;

    private JsonNode data;
}

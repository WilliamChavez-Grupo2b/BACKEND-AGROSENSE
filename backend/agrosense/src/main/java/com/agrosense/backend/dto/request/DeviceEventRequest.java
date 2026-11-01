package com.agrosense.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tools.jackson.databind.JsonNode;

@Data
public class DeviceEventRequest {

    @JsonProperty("device_id")
    private String deviceId;

    private String type;

    private JsonNode ts;

    private JsonNode data;

    private JsonNode ack;
}

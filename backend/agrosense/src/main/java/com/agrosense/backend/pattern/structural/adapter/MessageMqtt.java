package com.agrosense.backend.pattern.structural.adapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload a device publishes over MQTT, exactly as it arrives. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageMqtt {

    private String code;
    private Double value;
    private String unit;
    private String topic;
    /** Epoch milliseconds; optional. */
    private Long timestamp;
}

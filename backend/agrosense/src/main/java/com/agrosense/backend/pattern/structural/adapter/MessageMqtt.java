package com.agrosense.backend.pattern.structural.adapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageMqtt {

    private String code;
    private Double value;
    private String unit;
    private String topic;
    private Long timestamp;
}

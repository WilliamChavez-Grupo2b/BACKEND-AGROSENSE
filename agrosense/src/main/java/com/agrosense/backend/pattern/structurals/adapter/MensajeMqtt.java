package com.agrosense.backend.pattern.estructural.adapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MensajeMqtt {
    private String code;
    private Double value;
    private String unity;
    private String topic;
    private Long   timestamp;
}
package com.agrosense.backend.mqtt;

import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.pattern.structural.adapter.MessageMqtt;
import com.agrosense.backend.pattern.structural.adapter.MqttAdapter;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Handles one MQTT message: parse, adapt, record. Nothing a device sends may crash the listener, so every
 * failure is logged and the message is dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttMessageHandler {

    /** A reading is a few dozen bytes; anything much larger is not one. */
    static final int MAX_PAYLOAD_BYTES = 2048;

    private final ObjectMapper objectMapper;
    private final MqttAdapter adapter;
    private final SensorFacade sensorFacade;

    public void handle(String topic, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            log.warn("Mensaje MQTT descartado por tamaño en {}", topic);
            return;
        }
        try {
            MessageMqtt message = objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), MessageMqtt.class);
            message.setTopic(topic);
            sensorFacade.recordReading(adapter.adapt(message));
        } catch (ResourceNotFoundException exception) {
            log.warn("Mensaje MQTT de un sensor no registrado en {}", topic);
        } catch (BusinessRuleException | IllegalArgumentException exception) {
            log.warn("Mensaje MQTT rechazado en {}: {}", topic, exception.getMessage());
        } catch (Exception exception) {
            log.error("Error al procesar un mensaje MQTT en {}: {}", topic, exception.getMessage());
        }
    }
}

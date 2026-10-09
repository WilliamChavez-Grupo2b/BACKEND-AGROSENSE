package com.agrosense.backend.mqtt;

import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.pattern.structural.adapter.MessageMqtt;
import com.agrosense.backend.pattern.structural.adapter.MqttAdapter;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Handles one MQTT message: parse, adapt, check where it came from, record. Nothing a device sends may
 * crash the listener, so every failure is logged and the message is dropped.
 */
@Slf4j
@Component
public class MqttMessageHandler {

    /** A reading is a few dozen bytes; anything much larger is not one. */
    static final int MAX_PAYLOAD_BYTES = 2048;

    private static final String SINGLE_LEVEL_WILDCARD = "+";

    private final ObjectMapper objectMapper;
    private final MqttAdapter adapter;
    private final SensorFacade sensorFacade;
    private final String[] topicFilterLevels;

    /**
     * @param topicFilter the subscription, for example {@code agrosense/sensors/+/readings}; the level
     *        under its {@code +} is the code of the sensor that publishes there
     */
    public MqttMessageHandler(ObjectMapper objectMapper, MqttAdapter adapter, SensorFacade sensorFacade,
            @Value("${mqtt.topic:agrosense/sensors/+/readings}") String topicFilter) {
        this.objectMapper = objectMapper;
        this.adapter = adapter;
        this.sensorFacade = sensorFacade;
        this.topicFilterLevels = topicFilter.split("/", -1);
    }

    public void handle(String topic, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            log.warn("Mensaje MQTT descartado por tamaño en {}", topic);
            return;
        }
        try {
            MessageMqtt message = objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), MessageMqtt.class);
            message.setTopic(topic);
            SensorReadingData reading = adapter.adapt(message);
            // A device may only report for the sensor whose topic it publishes on. With one topic per
            // sensor, the broker's access rules then decide who can speak for each sensor.
            Optional<String> topicSensorCode = sensorCodeOf(topic);
            if (topicSensorCode.isEmpty() || !topicSensorCode.get().equalsIgnoreCase(reading.getSensorCode())) {
                log.warn("Mensaje MQTT descartado: el sensor {} no puede publicar en {}",
                        reading.getSensorCode(), topic);
                return;
            }
            // The facade accepts it only for a registered, active sensor of an active crop and account.
            sensorFacade.recordReading(reading);
        } catch (ResourceNotFoundException exception) {
            log.warn("Mensaje MQTT de un sensor no registrado en {}", topic);
        } catch (BusinessRuleException | IllegalArgumentException exception) {
            log.warn("Mensaje MQTT rechazado en {}: {}", topic, exception.getMessage());
        } catch (Exception exception) {
            log.error("Error al procesar un mensaje MQTT en {}: {}", topic, exception.getMessage());
        }
    }

    /** The topic level that sits under the filter's {@code +}; empty when the topic does not fit the filter. */
    private Optional<String> sensorCodeOf(String topic) {
        String[] levels = topic == null ? new String[0] : topic.split("/", -1);
        if (levels.length != topicFilterLevels.length) {
            return Optional.empty();
        }
        String sensorCode = null;
        for (int i = 0; i < levels.length; i++) {
            if (SINGLE_LEVEL_WILDCARD.equals(topicFilterLevels[i])) {
                sensorCode = sensorCode == null ? levels[i] : sensorCode;
            } else if (!topicFilterLevels[i].equals(levels[i])) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(sensorCode).filter(code -> !code.isBlank());
    }
}

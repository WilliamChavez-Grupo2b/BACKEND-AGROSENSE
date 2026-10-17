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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Component
public class MqttMessageHandler {

    static final int MAX_PAYLOAD_BYTES = 2048;

    private static final String SINGLE_LEVEL_WILDCARD = "+";

    private final ObjectMapper objectMapper;
    private final MqttAdapter adapter;
    private final SensorFacade sensorFacade;
    private final String[] topicFilterLevels;

    public MqttMessageHandler(ObjectMapper objectMapper, MqttAdapter adapter, SensorFacade sensorFacade,
            @Value("${mqtt.topic:agrosense/sensors/+/readings}") String topicFilter) {
        this.objectMapper = objectMapper;
        this.adapter = adapter;
        this.sensorFacade = sensorFacade;
        this.topicFilterLevels = topicFilter.split("/", -1);
    }

    public void handle(String topic, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            log.warn("MQTT message dropped for its size on {}", topic);
            return;
        }
        try {
            MessageMqtt message = objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), MessageMqtt.class);
            message.setTopic(topic);
            SensorReadingData reading = adapter.adapt(message);
            Optional<String> topicSensorCode = sensorCodeOf(topic);
            if (topicSensorCode.isEmpty() || !topicSensorCode.get().equalsIgnoreCase(reading.getSensorCode())) {
                log.warn("MQTT message dropped: sensor {} cannot publish on {}",
                        reading.getSensorCode(), topic);
                return;
            }
            sensorFacade.recordReading(reading);
        } catch (ResourceNotFoundException exception) {
            log.warn("MQTT message from an unregistered sensor on {}", topic);
        } catch (JacksonException exception) {
            log.warn("Unreadable MQTT message dropped on {}", topic);
        } catch (BusinessRuleException | IllegalArgumentException exception) {
            log.warn("MQTT message rejected on {}: {}", topic, exception.getMessage());
        } catch (Exception exception) {
            log.error("Error processing an MQTT message on {}: {}", topic, exception.getMessage());
        }
    }

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

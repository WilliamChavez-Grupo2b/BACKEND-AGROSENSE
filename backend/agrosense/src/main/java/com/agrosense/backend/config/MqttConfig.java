package com.agrosense.backend.config;

import com.agrosense.backend.mqtt.MqttMessageHandler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MqttConfig implements MqttCallbackExtended {

    private static final int QOS_AT_LEAST_ONCE = 1;

    private final MqttMessageHandler messageHandler;

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.topic}")
    private String topic;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    private MqttClient client;

    @Scheduled(initialDelay = 0, fixedDelay = 30_000)
    public synchronized void ensureConnected() {
        try {
            if (client == null) {
                client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                client.setCallback(this);
            }
            if (!client.isConnected()) {
                client.connect(connectOptions());
            }
        } catch (MqttException exception) {
            log.warn("Could not connect to the MQTT broker {}: {}", brokerUrl, exception.getMessage());
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }
        return options;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        try {
            client.subscribe(topic, QOS_AT_LEAST_ONCE);
            log.info("Connected to the MQTT broker {} and subscribed to {}", serverUri, topic);
        } catch (MqttException exception) {
            log.error("Could not subscribe to {}: {}", topic, exception.getMessage());
        }
    }

    @Override
    public void messageArrived(String messageTopic, MqttMessage message) {
        messageHandler.handle(messageTopic, message.getPayload());
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "no detail" : cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @PreDestroy
    public synchronized void disconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (MqttException exception) {
            log.warn("Error closing the MQTT connection: {}", exception.getMessage());
        }
    }
}

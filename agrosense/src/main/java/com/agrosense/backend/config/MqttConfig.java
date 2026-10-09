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

/**
 * Subscribes to the sensors topic of an MQTT broker. Only active with {@code mqtt.enabled=true}. The
 * application starts even when the broker is down and keeps trying to connect in the background.
 */
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

    /** Connects at startup and retries every 30 seconds until the first connection succeeds. */
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
            log.warn("No se pudo conectar al broker MQTT {}: {}", brokerUrl, exception.getMessage());
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
            log.info("Conectado al broker MQTT {} y suscrito a {}", serverUri, topic);
        } catch (MqttException exception) {
            log.error("No se pudo suscribir a {}: {}", topic, exception.getMessage());
        }
    }

    @Override
    public void messageArrived(String messageTopic, MqttMessage message) {
        messageHandler.handle(messageTopic, message.getPayload());
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("Conexión MQTT perdida: {}", cause == null ? "sin detalle" : cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // This client only subscribes.
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
            log.warn("Error al cerrar la conexión MQTT: {}", exception.getMessage());
        }
    }
}

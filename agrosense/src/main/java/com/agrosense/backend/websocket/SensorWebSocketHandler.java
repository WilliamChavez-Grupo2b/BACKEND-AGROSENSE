package com.agrosense.backend.websocket;

import com.agrosense.backend.event.AlertRaisedEvent;
import com.agrosense.backend.event.SensorReadingRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes new readings and alerts to their owner's connected clients. It listens after the transaction
 * commits, so clients are never told about data that was rolled back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorWebSocketHandler {

    public static final String READINGS_QUEUE = "/queue/readings";
    public static final String ALERTS_QUEUE = "/queue/alerts";

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReadingRecorded(SensorReadingRecordedEvent event) {
        send(event.ownerEmail(), READINGS_QUEUE, event.reading());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertRaised(AlertRaisedEvent event) {
        send(event.ownerEmail(), ALERTS_QUEUE, event.alert());
    }

    private void send(String ownerEmail, String queue, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(ownerEmail, queue, payload);
        } catch (Exception exception) {
            // A push that fails must not affect the reading that was already stored.
            log.warn("No se pudo enviar la notificación por WebSocket: {}", exception.getMessage());
        }
    }
}

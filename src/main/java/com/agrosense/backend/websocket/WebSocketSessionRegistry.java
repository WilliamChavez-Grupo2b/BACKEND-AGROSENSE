package com.agrosense.backend.websocket;

import com.agrosense.backend.security.TokenAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of the open WebSocket sessions and of the token each one connected with. A token is only
 * presented once, on connect, so without this a session would keep receiving data after its token
 * expired or its account was disabled. Such sessions are closed by a periodic check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    private static final CloseStatus SESSION_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Session expired");

    private record Credentials(String email, Instant expiresAt) {
    }

    private final TokenAuthenticator tokenAuthenticator;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Credentials> credentials = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                credentials.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Records who a session authenticated as and until when its token is valid. */
    public void authenticated(String sessionId, String email, Instant expiresAt) {
        credentials.put(sessionId, new Credentials(email, expiresAt));
    }

    /** Whether the session's token has expired. A session that never authenticated counts as expired. */
    public boolean isExpired(String sessionId) {
        Credentials session = credentials.get(sessionId);
        return session == null || !Instant.now().isBefore(session.expiresAt());
    }

    @Scheduled(fixedDelayString = "${websocket.session-check-ms:15000}")
    public void closeInvalidSessions() {
        closeInvalidSessions(Instant.now());
    }

    /**
     * Closes the sessions whose token has expired by {@code now} or whose account is no longer enabled.
     *
     * @return how many sessions were closed
     */
    public int closeInvalidSessions(Instant now) {
        Map<String, Boolean> activeAccounts = new HashMap<>();
        int closed = 0;
        for (Map.Entry<String, Credentials> entry : credentials.entrySet()) {
            Credentials session = entry.getValue();
            boolean valid = now.isBefore(session.expiresAt())
                    && activeAccounts.computeIfAbsent(session.email(), tokenAuthenticator::isActive);
            if (!valid && close(entry.getKey())) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("Sesiones WebSocket cerradas por token expirado o cuenta desactivada: {}", closed);
        }
        return closed;
    }

    private boolean close(String sessionId) {
        credentials.remove(sessionId);
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.close(SESSION_EXPIRED);
            return true;
        } catch (IOException exception) {
            log.warn("No se pudo cerrar la sesión WebSocket {}: {}", sessionId, exception.getMessage());
            return false;
        }
    }
}

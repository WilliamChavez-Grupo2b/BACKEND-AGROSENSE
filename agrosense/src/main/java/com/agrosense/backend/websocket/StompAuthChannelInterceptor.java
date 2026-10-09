package com.agrosense.backend.websocket;

import com.agrosense.backend.security.TokenAuthenticator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Guards the STOMP channel: a client must present a valid token when it connects, may only subscribe to
 * its own private queues, and cannot publish anything.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    static final String USER_QUEUE_PREFIX = "/user/queue/";

    private final TokenAuthenticator tokenAuthenticator;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> accessor.setUser(tokenAuthenticator
                    .authenticate(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION))
                    .orElseThrow(() -> new MessageDeliveryException("Authentication required")));
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (accessor.getUser() == null || destination == null
                        || !destination.startsWith(USER_QUEUE_PREFIX)) {
                    throw new MessageDeliveryException("Subscription not allowed");
                }
            }
            case SEND -> throw new MessageDeliveryException("Clients cannot publish messages");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK... need no extra checks.
            }
        }
        return message;
    }
}

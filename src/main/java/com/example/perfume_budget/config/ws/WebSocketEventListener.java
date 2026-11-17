package com.example.perfume_budget.config.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Tracks connected staff sessions per tenant in {@link AdminSessionManager} (SEC-03c). */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {
    private final AdminSessionManager adminSessionManager;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        if (event.getUser() instanceof StompTenantAuthentication user
                && StompDestinationPolicy.isStaff(user) && headerAccessor.getSessionId() != null) {
            adminSessionManager.addAdmin(user.getTenantSlug(), user.getEmail(), headerAccessor.getSessionId());
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        if (event.getUser() instanceof StompTenantAuthentication user && event.getSessionId() != null) {
            adminSessionManager.removeAdmin(user.getTenantSlug(), user.getEmail(), event.getSessionId());
        }
    }
}

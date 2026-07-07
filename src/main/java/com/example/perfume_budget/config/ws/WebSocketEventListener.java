package com.example.perfume_budget.config.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {
    private final AdminSessionManager adminSessionManager;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = headerAccessor.getUser();

        if (principal instanceof Authentication authentication) {
            Set<String> allowedRoles = Set.of("ROLE_ADMIN", "ROLE_FRONT_DESK");
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> allowedRoles.contains(a.getAuthority()));

            if (isAdmin && !adminSessionManager.isAdminConnected(principal.getName())) {
                adminSessionManager.addAdmin(principal.getName());
            }
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = headerAccessor.getUser();

        if (principal != null) {
            adminSessionManager.removeAdmin(principal.getName());
        }
    }
}

package com.example.perfume_budget.config.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Authenticated STOMP sessions and their sockets (SEC-03e F5). A token is checked once, at CONNECT, so without this an
 * open socket survived logout-time events: the user being disabled, the tenant being suspended and the CONNECT token
 * expiring (the expiry was only enforced on the next SUBSCRIBE/SEND, while existing subscriptions kept receiving).
 * {@link StompSessionRevalidator} walks this registry and closes what is no longer valid.
 */
@Slf4j
@Component
public class StompSessionRegistry {

    /** What a session authenticated as. */
    public record Auth(String tenant, String email, long expiresAtMillis) { }

    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, Auth> sessions = new ConcurrentHashMap<>();

    void socketOpened(WebSocketSession session) {
        sockets.put(session.getId(), session);
    }

    void socketClosed(String sessionId) {
        sockets.remove(sessionId);
        sessions.remove(sessionId);
    }

    void authenticated(String sessionId, String tenant, String email, long expiresAtMillis) {
        sessions.put(sessionId, new Auth(tenant, email, expiresAtMillis));
    }

    /** A copy: session id to what it authenticated as. */
    public Map<String, Auth> snapshot() {
        return new HashMap<>(sessions);
    }

    public int size() {
        return sessions.size();
    }

    /** Closes every session whose authentication matches; returns how many sockets were closed. */
    public int closeWhere(Predicate<Auth> matches, String reason) {
        int closed = 0;
        for (Map.Entry<String, Auth> e : snapshot().entrySet()) {
            if (matches.test(e.getValue()) && close(e.getKey(), reason)) {
                closed++;
            }
        }
        return closed;
    }

    public int closeTenant(String tenantSlug, String reason) {
        return closeWhere(a -> a.tenant().equals(tenantSlug), reason);
    }

    public int closeUser(String tenantSlug, String email, String reason) {
        return closeWhere(a -> a.tenant().equals(tenantSlug) && a.email().equals(email), reason);
    }

    private boolean close(String sessionId, String reason) {
        WebSocketSession socket = sockets.get(sessionId);
        sessions.remove(sessionId);
        if (socket == null) {
            return false;
        }
        try {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
            log.info("STOMP session closed: {}", reason);
            return true;
        } catch (Exception e) {
            log.warn("Closing a STOMP session failed: {}", e.getClass().getSimpleName());
            return false;
        } finally {
            sockets.remove(sessionId);
        }
    }
}

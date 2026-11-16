package com.example.perfume_budget.config.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connected staff STOMP sessions, keyed by tenant (SEC-03c / WS-01): tenant slug -> staff e-mail -> session ids. A
 * lookup for tenant A never returns tenant B's admins, even when both use the same e-mail; an admin stays connected
 * until their last session (browser tab) disconnects.
 */
@Slf4j
@Component
public class AdminSessionManager {

    private final Map<String, Map<String, Set<String>>> sessions = new ConcurrentHashMap<>();

    public void addAdmin(String tenantSlug, String email, String sessionId) {
        sessions.computeIfAbsent(tenantSlug, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(email, e -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
        log.info("Staff session connected for tenant {}. Connected staff in tenant: {}", tenantSlug,
                sessions.getOrDefault(tenantSlug, Map.of()).size());
    }

    public void removeAdmin(String tenantSlug, String email, String sessionId) {
        sessions.computeIfPresent(tenantSlug, (t, byEmail) -> {
            byEmail.computeIfPresent(email, (e, ids) -> {
                ids.remove(sessionId);
                return ids.isEmpty() ? null : ids;
            });
            return byEmail.isEmpty() ? null : byEmail;
        });
        log.info("Staff session disconnected for tenant {}.", tenantSlug);
    }

    /** E-mails of the staff of {@code tenantSlug} with at least one open session (never another tenant's). */
    public Set<String> getConnectedAdmins(String tenantSlug) {
        if (tenantSlug == null) {
            return Set.of();
        }
        Map<String, Set<String>> byEmail = sessions.get(tenantSlug);
        return byEmail == null ? Set.of() : Set.copyOf(byEmail.keySet());
    }

    public boolean isAdminConnected(String tenantSlug, String email) {
        return getConnectedAdmins(tenantSlug).contains(email);
    }
}

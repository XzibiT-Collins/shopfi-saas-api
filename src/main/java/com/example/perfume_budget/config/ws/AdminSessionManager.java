package com.example.perfume_budget.config.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AdminSessionManager {
    // Stores the usernames of connected admins
    private final Set<String> connectedAdmins = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addAdmin(String username) {
        connectedAdmins.add(username);
        log.info("Admin connected: {}. Total connected admins: {}", username, connectedAdmins.size());
    }

    public void removeAdmin(String username) {
        connectedAdmins.remove(username);
        log.info("Admin disconnected: {}. Total connected admins: {}", username, connectedAdmins.size());
    }

    public Set<String> getConnectedAdmins() {
        return Collections.unmodifiableSet(connectedAdmins);
    }

    public boolean isAdminConnected(String username) {
        return connectedAdmins.contains(username);
    }
}

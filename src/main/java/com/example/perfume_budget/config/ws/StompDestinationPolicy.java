package com.example.perfume_budget.config.ws;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * Which destinations a STOMP client may SUBSCRIBE / SEND to (SEC-03c). Default deny, exact match only: the simple
 * broker matches subscriptions as Ant patterns, so a subscription to e.g. {@code /queue/**} or a guessed
 * {@code /queue/notifications-user<sessionId>} would otherwise receive other users' pushes.
 *
 * <ul>
 *   <li>SUBSCRIBE {@code /user/queue/notifications}: ADMIN and FRONT_DESK (the staff notification queue). Spring
 *       resolves it to the subscriber's own session queue.</li>
 *   <li>Everything else, and every SEND (there are no {@code @MessageMapping} handlers): denied.</li>
 * </ul>
 */
public final class StompDestinationPolicy {

    public static final String STAFF_NOTIFICATIONS = "/user/queue/notifications";

    static final Set<String> STAFF_ROLES = Set.of("ROLE_ADMIN", "ROLE_FRONT_DESK");

    private StompDestinationPolicy() {
    }

    public static boolean maySubscribe(Authentication user, String destination) {
        return STAFF_NOTIFICATIONS.equals(destination) && isStaff(user);
    }

    public static boolean maySend(Authentication user, String destination) {
        return false;
    }

    static boolean isStaff(Authentication user) {
        if (user == null) {
            return false;
        }
        for (GrantedAuthority a : user.getAuthorities()) {
            if (STAFF_ROLES.contains(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}

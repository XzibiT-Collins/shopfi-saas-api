package com.example.perfume_budget.config.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC-03c / WS-01: staff sessions are keyed by tenant and counted per session. */
class AdminSessionManagerTest {

    private final AdminSessionManager sessions = new AdminSessionManager();

    @Test
    void tenantsNeverSeeEachOthersAdminsEvenWithTheSameEmail() {
        sessions.addAdmin("acme", "staff@shared.test", "s1");
        assertThat(sessions.getConnectedAdmins("acme")).containsExactly("staff@shared.test");
        assertThat(sessions.getConnectedAdmins("bravo")).isEmpty();
        assertThat(sessions.isAdminConnected("bravo", "staff@shared.test")).isFalse();
        assertThat(sessions.getConnectedAdmins(null)).isEmpty();

        sessions.addAdmin("bravo", "staff@shared.test", "s2");
        sessions.removeAdmin("bravo", "staff@shared.test", "s2");
        assertThat(sessions.isAdminConnected("acme", "staff@shared.test")).as("bravo's disconnect leaves acme alone").isTrue();
        assertThat(sessions.isAdminConnected("bravo", "staff@shared.test")).isFalse();
    }

    @Test
    void anAdminStaysConnectedUntilTheLastSessionCloses() {
        sessions.addAdmin("acme", "admin@acme.test", "tab1");
        sessions.addAdmin("acme", "admin@acme.test", "tab2");
        sessions.removeAdmin("acme", "admin@acme.test", "tab1");
        assertThat(sessions.isAdminConnected("acme", "admin@acme.test")).isTrue();
        sessions.removeAdmin("acme", "admin@acme.test", "tab2");
        assertThat(sessions.isAdminConnected("acme", "admin@acme.test")).isFalse();
        sessions.removeAdmin("acme", "admin@acme.test", "tab2"); // idempotent
        sessions.removeAdmin("nope", "x", "y");
        assertThat(sessions.getConnectedAdmins("acme")).isEmpty();
    }

    @Test
    void returnedSetIsASnapshot() {
        sessions.addAdmin("acme", "a@acme.test", "s1");
        var snapshot = sessions.getConnectedAdmins("acme");
        sessions.addAdmin("acme", "b@acme.test", "s2");
        assertThat(snapshot).containsExactly("a@acme.test");
    }
}

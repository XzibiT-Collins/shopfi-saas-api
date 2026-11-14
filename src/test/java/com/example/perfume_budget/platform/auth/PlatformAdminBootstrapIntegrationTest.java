package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** AC3 against the real master.platform_admins table: insert-only, idempotent, env change never overwrites. */
class PlatformAdminBootstrapIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired PlatformAdminRepository admins;
    @Autowired PasswordEncoder encoder;

    @Test
    void bootstrapTwice_createsOnce_andChangedEnvDoesNotOverwriteTheExistingAdmin() {
        String email = "boot-once@platform.test";
        assertThat(new PlatformAdminBootstrap(admins, encoder, email, "First-password-1!", "Boss").bootstrap()).isTrue();
        String hash = admins.findByEmail(email).orElseThrow().getPassword();
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("First-password-1!", hash)).isTrue();

        assertThat(new PlatformAdminBootstrap(admins, encoder, email, "First-password-1!", "Boss").bootstrap()).isFalse();
        assertThat(new PlatformAdminBootstrap(admins, encoder, email.toUpperCase(), "Changed-password-2!", "Other").bootstrap()).isFalse();

        var after = admins.findByEmail(email).orElseThrow();
        assertThat(after.getPassword()).isEqualTo(hash);
        assertThat(after.getFullName()).isEqualTo("Boss");
        assertThat(admins.findAll().stream().filter(a -> a.getEmail().equals(email))).hasSize(1);
    }

    @Test
    void bootstrapNeverReactivatesOrRewritesADeactivatedAdmin() {
        String email = "boot-inactive@platform.test";
        new PlatformAdminBootstrap(admins, encoder, email, "First-password-1!", "").bootstrap();
        var a = admins.findByEmail(email).orElseThrow();
        a.setActive(false);
        admins.save(a);
        new PlatformAdminBootstrap(admins, encoder, email, "First-password-1!", "").bootstrap();
        assertThat(admins.findByEmail(email).orElseThrow().isActive()).isFalse();
    }
}

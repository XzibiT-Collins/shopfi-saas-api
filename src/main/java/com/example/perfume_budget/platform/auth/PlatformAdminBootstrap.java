package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Creates the first SUPER_ADMIN from {@code PLATFORM_ADMIN_EMAIL} / {@code PLATFORM_ADMIN_PASSWORD} (and optional
 * {@code PLATFORM_ADMIN_FULL_NAME}) at startup.
 *
 * <p>Insert-only and idempotent: an existing account (matched by email) is never modified, so changing the env
 * later does NOT reset a password; rotate it in the database. When the variables are unset the bootstrap is
 * skipped with a WARN and no DB access (an operator can remove the secret after first boot; startup never fails).
 * Values (and the password length) are never logged. A weak or oversized password is refused, not truncated.
 */
@Slf4j
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_PASSWORD_BYTES = 72;

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String fullName;

    public PlatformAdminBootstrap(PlatformAdminRepository admins, PasswordEncoder passwordEncoder,
                                  @Value("${platform.admin.email:}") String email,
                                  @Value("${platform.admin.password:}") String password,
                                  @Value("${platform.admin.full-name:}") String fullName) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return true when a new admin was created. */
    boolean bootstrap() {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            log.warn("PLATFORM_ADMIN_EMAIL / PLATFORM_ADMIN_PASSWORD not set; platform admin bootstrap skipped "
                    + "(fine once an admin exists)");
            return false;
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || normalized.length() > 254) {
            log.warn("PLATFORM_ADMIN_EMAIL is not a valid email address; platform admin bootstrap skipped");
            return false;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            log.warn("PLATFORM_ADMIN_PASSWORD must be {} to {} bytes; platform admin bootstrap skipped",
                    MIN_PASSWORD_LENGTH, MAX_PASSWORD_BYTES);
            return false;
        }
        if (admins.existsByEmailIgnoreCase(normalized)) {
            log.info("Platform super-admin already exists; bootstrap left it unchanged");
            return false;
        }
        try {
            admins.saveAndFlush(PlatformAdmin.builder()
                    .email(normalized)
                    .password(passwordEncoder.encode(password))
                    .fullName(fullName == null || fullName.isBlank() ? "Platform Admin" : fullName.strip())
                    .isActive(true)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.info("Platform super-admin was created concurrently; bootstrap left it unchanged");
            return false;
        }
        log.info("Bootstrapped the first platform super-admin");
        return true;
    }
}

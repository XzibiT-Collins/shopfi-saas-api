package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the tenant admin user. Idempotent and invoked per-tenant (under an active
 * {@code TenantContext}) by {@link TenantSeeder} during provisioning — NOT a boot-time runner,
 * so it never writes to the neutral/template schema.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public void seedAdmin(String email, String rawPassword, String fullName) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            log.warn("Admin email/password missing; skipping admin seed for current tenant");
            return;
        }

        String normalizedEmail = email.strip().toLowerCase();
        String strippedPassword = rawPassword.strip();
        String name = (fullName == null || fullName.isBlank()) ? "Admin" : fullName.strip();

        userRepository.findByEmail(normalizedEmail).ifPresentOrElse(
                admin -> {
                    if (!passwordEncoder.matches(strippedPassword, admin.getPassword())) {
                        admin.setPassword(passwordEncoder.encode(strippedPassword));
                        userRepository.save(admin);
                        log.info("Updated existing admin password for {}", normalizedEmail);
                    }
                },
                () -> {
                    User admin = User.builder()
                            .fullName(name)
                            .email(normalizedEmail)
                            .password(passwordEncoder.encode(strippedPassword))
                            .roles(UserRole.ADMIN)
                            .authProvider(AuthProvider.LOCAL)
                            .isActive(true)
                            .emailVerified(true)
                            .build();
                    userRepository.save(admin);
                    log.info("Seeded admin user {}", normalizedEmail);
                }
        );
    }
}

package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    @Value("${perfume.budget.admin.email}")
    private String adminEmail;

    @Value("${perfume.budget.admin.password}")
    private String adminPassword;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminPassword == null || adminEmail.isBlank() || adminPassword.isBlank()) {
            log.warn("Admin email or password not configured, skipping seed.");
            return;
        }

        String normalizedEmail = adminEmail.strip().toLowerCase();
        String strippedPassword = adminPassword.strip();

        userRepository.findByEmail(normalizedEmail).ifPresentOrElse(
                admin -> {
                    if (!passwordEncoder.matches(strippedPassword, admin.getPassword())) {
                        log.info("Updating existing admin password.");
                        admin.setPassword(passwordEncoder.encode(strippedPassword));
                        userRepository.save(admin);
                    }
                    log.info("Admin user already exists and password is up to date.");
                },
                () -> {
                    User admin = User.builder()
                            .fullName("Admin")
                            .email(normalizedEmail)
                            .password(passwordEncoder.encode(strippedPassword))
                            .roles(UserRole.ADMIN)
                            .authProvider(AuthProvider.LOCAL)
                            .isActive(true)
                            .emailVerified(true)
                            .build();

                    userRepository.save(admin);
                    log.info("Admin user seeded successfully with email: {}", normalizedEmail);
                }
        );
    }
}

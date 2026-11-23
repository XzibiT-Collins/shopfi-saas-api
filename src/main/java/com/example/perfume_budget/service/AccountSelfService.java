package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.InactiveAccountException;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.validation.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-RD-08: a signed-in tenant user edits their OWN record. The target is always the id of the authenticated
 * principal (never a request value), and the row is read through the tenant-scoped repository, so a user can only ever
 * reach their own row in their own tenant schema.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSelfService {

    public static final String CODE_CURRENT_PASSWORD_INCORRECT = "CURRENT_PASSWORD_INCORRECT";
    public static final String CODE_NO_LOCAL_PASSWORD = "NO_LOCAL_PASSWORD";
    static final String MSG_CURRENT_PASSWORD_INCORRECT = "Current password is incorrect";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User updateFullName(Long userId, String rawFullName) {
        String fullName = rawFullName == null ? "" : rawFullName.strip();
        if (fullName.isEmpty()) {
            throw new BadRequestException("Full name is required");
        }
        if (fullName.length() > 100) {
            throw new BadRequestException("Full name must be at most 100 characters");
        }
        if (fullName.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("Full name contains invalid characters");
        }
        User user = activeUser(userId);
        user.setFullName(fullName);
        return userRepository.save(user);
    }

    /**
     * Verifies the CURRENT password, applies the signup/reset policy, refuses reuse, stores the new hash and bumps the
     * user's {@code token_version} in ONE transaction, so every token minted before (any device) stops working. The
     * row is locked first: two concurrent changes serialise and the second one is checked against the new hash.
     *
     * @return the saved user (carrying the new token version, for re-issuing the caller's own session)
     */
    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword, String confirmPassword) {
        User user = userRepository.lockById(userId)
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        if (!user.isActive()) {
            throw new InactiveAccountException("User account is inactive");
        }
        if (user.getAuthProvider() == AuthProvider.GOOGLE || user.getPassword() == null || user.getPassword().isBlank()) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, CODE_NO_LOCAL_PASSWORD,
                    "This account signs in with Google and has no password to change. Use \"Forgot password\" to set one.");
        }
        // Login strips before authenticating, so the stripped value is what is compared, validated and stored.
        if (!passwordEncoder.matches(PasswordPolicy.normalize(currentPassword), user.getPassword())) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, CODE_CURRENT_PASSWORD_INCORRECT,
                    MSG_CURRENT_PASSWORD_INCORRECT);
        }
        String violation = PasswordPolicy.violation(newPassword);
        if (violation != null) {
            throw new BadRequestException(violation);
        }
        String normalized = PasswordPolicy.normalize(newPassword);
        if (confirmPassword != null && !normalized.equals(PasswordPolicy.normalize(confirmPassword))) {
            throw new BadRequestException("Passwords do not match");
        }
        if (passwordEncoder.matches(normalized, user.getPassword())) {
            throw new BadRequestException("New password cannot be same as current password");
        }
        user.setPassword(passwordEncoder.encode(normalized));
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.saveAndFlush(user);
        log.info("Password changed for user {}; earlier sessions revoked", saved.getId());
        return saved;
    }

    private User activeUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BadCredentialsException("User not found"));
        if (!user.isActive()) {
            throw new InactiveAccountException("User account is inactive");
        }
        return user;
    }
}

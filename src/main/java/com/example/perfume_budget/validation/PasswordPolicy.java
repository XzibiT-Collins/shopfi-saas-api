package com.example.perfume_budget.validation;

import java.nio.charset.StandardCharsets;

/**
 * Server-side password policy shared by registration and password reset. It evaluates the STRIPPED password because
 * login strips ({@code String.strip()}) before authenticating, so the stripped value is the one that is stored and
 * must therefore be the one that is validated. Rules (same as the storefront client): at least 8 UTF-16 code units,
 * at most 72 UTF-8 bytes (BCrypt ignores anything beyond 72 bytes), one ASCII uppercase letter, one ASCII digit and
 * one special character from {@link #SPECIALS}.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;
    public static final String SPECIALS = "!@#$%^&*()_+-=[]{};':\"\\|,.<>/?";

    public static final String MSG_COMPOSITION =
            "Password must contain at least one uppercase letter, one number, and one special character.";
    public static final String MSG_TOO_SHORT = "Password must be at least 8 characters long.";
    public static final String MSG_TOO_LONG = "Password must be at most 72 bytes long.";

    private PasswordPolicy() {
    }

    /** The value that is compared, validated, hashed and later matched at login. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip();
    }

    /** @return {@code null} when the password is acceptable, otherwise the violation message. */
    public static String violation(String raw) {
        String p = normalize(raw);
        if (p == null || p.isEmpty()) {
            return MSG_TOO_SHORT;
        }
        if (p.length() < MIN_LENGTH) {
            return MSG_TOO_SHORT;
        }
        if (p.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return MSG_TOO_LONG;
        }
        boolean upper = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else if (SPECIALS.indexOf(c) >= 0) {
                special = true;
            }
        }
        return upper && digit && special ? null : MSG_COMPOSITION;
    }
}

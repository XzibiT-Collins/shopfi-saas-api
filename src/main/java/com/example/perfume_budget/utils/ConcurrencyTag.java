package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.ApiCodeException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API-RD-11c: optimistic-concurrency token for the team and tax saves that have no version column (front-desk default
 * template, a staff member's permission overrides, a tax). The token is a SHA-256 prefix of the canonical state, so it
 * needs no migration and changes exactly when the saved state changes. The save compares the caller's
 * {@code If-Match} with the token of the state it read under a lock (row lock / advisory lock), so the check and the
 * write are atomic. Same contract shape as the order status change: header {@code If-Match} (the {@code ETag} of the last
 * read), stale = 409 with a code, absent header = last write wins (deprecated, documented).
 */
public final class ConcurrencyTag {

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private ConcurrencyTag() {
    }

    /** The token of a canonical state string. */
    public static String of(String canonicalState) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalState.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code ETag} header value for a token. */
    public static String header(String token) {
        return "\"" + token + "\"";
    }

    /**
     * Parses an {@code If-Match} value: {@code "tok"}, {@code W/"tok"} or bare {@code tok}. Null / blank / {@code *}
     * means "no precondition" (null). Several values or anything that is not a token we issued: 400 INVALID_IF_MATCH.
     */
    public static String parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.strip();
        if (v.equals("*")) {
            return null;
        }
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        if (!TOKEN.matcher(v).matches()) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH",
                    "If-Match must be the ETag returned by the last read of this resource.");
        }
        return v;
    }

    /** 409 with {@code conflictCode} and the current token in {@code data.currentVersion} when a precondition was sent and differs. */
    public static void require(String expected, String current, String conflictCode, String message) {
        if (expected != null && !expected.equals(current)) {
            throw new ApiCodeException(HttpStatus.CONFLICT, conflictCode, message, Map.of("currentVersion", current));
        }
    }
}

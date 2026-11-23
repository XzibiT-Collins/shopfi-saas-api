package com.example.perfume_budget.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API-RD-03 AC3: SHA-256 of what a cancel / refund request asked for (the action and the stripped reason; the order is
 * part of the key's scope, not of the hash). A replay with the same key must repeat both, otherwise it is a different
 * request on a used key. The reason is length-prefixed so no reason text can imitate another field.
 */
public final class WalkInReversalFingerprint {

    private WalkInReversalFingerprint() {
    }

    public static String of(String action, String reason) {
        String r = reason == null ? "" : reason.strip();
        String canonical = "v1|action=" + action + "|reason=" + r.length() + ":" + r;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

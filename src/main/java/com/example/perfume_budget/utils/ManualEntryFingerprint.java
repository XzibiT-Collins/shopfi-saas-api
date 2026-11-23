package com.example.perfume_budget.utils;

import com.example.perfume_budget.dto.accounts.request.JournalEntryLineRequest;
import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Normalisation for manual journal entry idempotency (API-RD-12d). SHA-256 of the request after normalisation (line
 * order and amount scale do not matter; text is length-prefixed so a value cannot bleed into its neighbour). Only the
 * hash is stored, never the body.
 */
public final class ManualEntryFingerprint {

    private ManualEntryFingerprint() {
    }

    public static String of(ManualJournalEntryRequest r) {
        String lines = r.lines().stream()
                .map((JournalEntryLineRequest l) -> l.accountCategory() + ":" + l.entryType() + ":"
                        + l.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString() + ":" + lp(l.description()))
                .sorted()
                .collect(Collectors.joining(","));
        String canonical = "v1|description=" + lp(r.description()) + "|type=" + r.type() + "|lines=" + lines;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lp(String v) {
        String n = v == null || v.isBlank() ? "" : v.strip();
        return n.length() + ":" + n + ";";
    }
}

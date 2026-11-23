package com.example.perfume_budget.utils;

import com.example.perfume_budget.dto.walk_in.request.WalkInCustomerRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Normalisation for walk-in sale idempotency (API-RD-03 AC2). The fingerprint is a SHA-256 of the request body after
 * normalisation (line order, amount scale and blank-vs-null customer fields do not matter), so a retry that is
 * serialised differently still matches while any real change does not. Only the hash is stored, never the body.
 */
public final class WalkInFingerprint {

    private WalkInFingerprint() {
    }

    public static String of(WalkInOrderRequest r) {
        String lines = r.items().stream()
                .map((WalkInOrderItemRequest i) -> i.productId() + ":" + i.quantity())
                .sorted()
                .collect(Collectors.joining(","));
        WalkInCustomerRequest c = r.walkInCustomer();
        String canonical = "v1|registered=" + r.registeredUserId()
                + "|customer=" + (c == null ? "" : lp(c.name()) + lp(c.phone()) + lp(c.email()))
                + "|items=" + lines
                + "|discountType=" + r.discountType()
                + "|discountValue=" + money(r.discountValue())
                + "|method=" + r.paymentMethod()
                + "|paid=" + money(r.amountPaid())
                + "|splitCash=" + money(r.splitCashAmount())
                + "|splitMobile=" + money(r.splitMobileAmount());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Length-prefixed, so no field value can bleed into its neighbour ("a","b/c" vs "a/b","c"). */
    private static String lp(String v) {
        String n = blank(v);
        return n.length() + ":" + n + ";";
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? "" : v.strip();
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}

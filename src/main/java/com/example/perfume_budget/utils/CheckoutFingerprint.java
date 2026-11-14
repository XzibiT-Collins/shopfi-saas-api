package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Normalisation helpers for checkout idempotency. */
public final class CheckoutFingerprint {

    /** Idempotency-Key: 8..64 chars of letters, digits, dot, underscore, colon, hyphen. */
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

    private CheckoutFingerprint() {
    }

    /** SHA-256 hex of the normalised request: cart lines (product:qty, sorted), coupon (upper-cased), delivery detail. */
    public static String of(Cart cart, String normalizedCoupon, Long deliveryDetailId) {
        String lines = cart.getItems().stream()
                .map((CartItem i) -> i.getProduct().getId() + ":" + i.getQuantity())
                .sorted()
                .collect(Collectors.joining(","));
        String canonical = "v1|cart=" + lines + "|coupon=" + normalizedCoupon + "|delivery=" + deliveryDetailId;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String normalizeCoupon(String couponCode) {
        return couponCode == null || couponCode.isBlank() ? "" : couponCode.strip().toUpperCase();
    }
}

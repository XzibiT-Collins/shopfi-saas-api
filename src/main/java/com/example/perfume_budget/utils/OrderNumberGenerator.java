package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.OrderNumberClaimRepository;
import com.example.perfume_budget.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Order numbers are {@code PB-yyyyMMdd-<10 chars>}: the suffix is 10 symbols of a 30-symbol alphabet drawn from a
 * {@link SecureRandom} (about 49 bits), so numbers cannot be guessed or enumerated (ORD-01; the old suffix was 5 hex chars,
 * 20 bits from a UUID). Old numbers stay valid: lookups only compare the stored string.
 *
 * <p>Collision safe: a candidate is rejected when an order already has it, and is claimed atomically
 * ({@code INSERT ... ON CONFLICT DO NOTHING}) so two concurrent checkouts can never both take it; {@code orders.order_number}
 * stays UNIQUE as the last backstop. Up to {@link #MAX_ATTEMPTS} candidates are tried.
 */
@Component
@RequiredArgsConstructor
public class OrderNumberGenerator {
    private final OrderRepository orderRepository;
    private final OrderNumberClaimRepository claimRepository;
    private static final String PREFIX = "PB";
    static final int SUFFIX_LENGTH = 10;
    /** No 0/O/1/I/L/U (30 symbols): unambiguous when read out or typed. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateOrderNumber() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String orderNumber = buildOrderNumber();
            if (orderRepository.existsByOrderNumber(orderNumber)) {
                continue;
            }
            if (claimRepository.claim(orderNumber)) {
                return orderNumber;
            }
        }
        throw new IllegalStateException("Failed to generate unique order number after " + MAX_ATTEMPTS + " attempts");
    }

    private String buildOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return PREFIX + "-" + datePart + "-" + suffix;
    }
}

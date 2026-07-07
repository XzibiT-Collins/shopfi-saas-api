package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentReferenceGenerator {
    private final PaymentRepository paymentRepository;
    private static final String PREFIX = "TNX";
    private static final int MAX_ATTEMPTS = 5;

    public String generateReference() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String reference = buildReference();
            if (!paymentRepository.existsBySystemReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Failed to generate unique payment reference after " + MAX_ATTEMPTS + " attempts");
    }

    private String buildReference() {
        String timestampPart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomPart = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 5)
                .toUpperCase();
        return PREFIX + "-" + timestampPart + "-" + randomPart;
    }
}

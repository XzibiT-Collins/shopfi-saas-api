package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CouponCodeGenerator {
    private final CouponRepository couponRepository;
    private static final int MAX_ATTEMPTS = 5;

    public String generateCouponCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = buildCouponCode();
            if (!couponRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique coupon code after " + MAX_ATTEMPTS + " attempts");
    }

    private String buildCouponCode() {
        String yearPart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy"));
        String randomPart = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 6)
                .toUpperCase();
        return randomPart + yearPart;
    }
}

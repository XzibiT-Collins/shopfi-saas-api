package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderNumberGenerator {
    private final OrderRepository orderRepository;
    private static final String PREFIX = "PB";
    private static final int MAX_ATTEMPTS = 5;

    public String generateOrderNumber() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String orderNumber = buildOrderNumber();
            if (!orderRepository.existsByOrderNumber(orderNumber)) {
                return orderNumber;
            }
        }
        throw new IllegalStateException("Failed to generate unique order number after " + MAX_ATTEMPTS + " attempts");
    }

    private String buildOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        return PREFIX + "-" + datePart + "-" + randomPart;
    }
}

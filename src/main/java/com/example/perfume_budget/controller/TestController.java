package com.example.perfume_budget.controller;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {
    private final NotificationService notificationService;

    @PostMapping("/ws")
    public ResponseEntity<Void> testWebSocket(){
        Order mockOrder = Order.builder()
                .orderNumber("123456")
                .totalAmount(new Money(BigDecimal.valueOf(5000), CurrencyCode.GHS))
                .build();
        Payment mockPayment = Payment.builder()
                .id(1L)
                .order(mockOrder)
                .systemReference("123456")
                .customerEmail("test@gmail.com")
                .amount(BigDecimal.valueOf(1000))
                .paidAt(LocalDateTime.now())
                .build();

        notificationService.notifyAdminsOfSuccessfulPayment(mockPayment);
        return ResponseEntity.ok().build();
    }
}

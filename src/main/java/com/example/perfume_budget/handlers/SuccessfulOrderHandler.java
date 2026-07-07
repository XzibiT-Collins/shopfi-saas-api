package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SuccessfulOrderHandler {
    private final EmailService emailService;
    private final NotificationService notificationService;

    @Async
    @EventListener
    public void handlePaymentSuccess(Payment payment){
        log.info("Payment Successful Event received");
        emailService.sendAdminOrderNotification(payment);
        emailService.sendCustomerReceipt(payment);
        notificationService.notifyAdminsOfSuccessfulPayment(payment);
    }

    @Async
    @EventListener
    public void handleOrderCreationEvent(Order order){
        emailService.notifyAdminOnSuccessfulOrderCreation(order);
    }
}

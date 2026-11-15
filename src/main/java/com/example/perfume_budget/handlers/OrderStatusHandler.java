package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusHandler {
    private final EmailService emailService;

    @Async
    // After commit (ORD-01): a rolled-back status change must never e-mail the customer. Outside a transaction it runs at once.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderStatusChangeEvent(OrderStatusChangeEvent event){
        log.info("Order status change event received for user: {} with order number: {}", event.customerEmail(), event.orderNumber());
        emailService.notifyCustomerOnOrderStatusChange(event);
    }
}

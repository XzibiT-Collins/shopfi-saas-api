package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusHandler {
    private final EmailService emailService;
    private final MailDispatcher mailDispatcher;

    /**
     * After commit (ORD-01): a rolled-back status change must never e-mail the customer, and the retried admin /
     * expiry transaction e-mails once (only the attempt that commits gets here). Outside a transaction it runs at
     * once. EVT-01: the send runs on the dedicated mail executor; a lost e-mail is logged with the order number.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderStatusChangeEvent(OrderStatusChangeEvent event){
        String reference = MailDispatcher.reference(event);
        mailDispatcher.submit("order status e-mail", reference, () -> {
            log.info("Order status change e-mail for {}", reference);
            emailService.notifyCustomerOnOrderStatusChange(event);
        });
    }
}

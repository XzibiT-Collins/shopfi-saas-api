package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class SuccessfulOrderHandler {
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final PaymentRepository paymentRepository;
    private final TransactionTemplate readTx;

    public SuccessfulOrderHandler(EmailService emailService, NotificationService notificationService,
                                  PaymentRepository paymentRepository, PlatformTransactionManager txManager) {
        this.emailService = emailService;
        this.notificationService = notificationService;
        this.paymentRepository = paymentRepository;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /**
     * Receipt, admin e-mail and admin WebSocket / in-app notification for a settled payment.
     *
     * <p>API-04 rework 3: AFTER COMMIT only. The payment is settled inside a transaction that the webhook / verify
     * retry may run several times (a deadlock, or the deferred coupon UPDATE hitting COUPON_BUSY at commit, rolls an
     * attempt back); each attempt publishes the event, but only the attempt that commits reaches this method, and an
     * attempt that never commits sends nothing. Outside a transaction it runs at once ({@code fallbackExecution}).
     *
     * <p>The event's entity belongs to the publishing transaction's (now closed) session, so the payment, its order,
     * the order's customer and items are re-read here, in the committed state, before anything is rendered.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handlePaymentSuccess(Payment payment) {
        log.info("Payment Successful Event received");
        Payment committed = reload(payment);
        if (committed == null) {
            log.warn("Settled payment {} not found after commit; no receipt sent", payment.getId());
            return;
        }
        emailService.sendAdminOrderNotification(committed);
        emailService.sendCustomerReceipt(committed);
        notificationService.notifyAdminsOfSuccessfulPayment(committed);
    }

    private Payment reload(Payment payment) {
        if (payment.getId() == null) {
            return payment;
        }
        return readTx.execute(status -> paymentRepository.findById(payment.getId())
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(p -> {
                    Order order = p.getOrder();
                    Hibernate.initialize(order);
                    Hibernate.initialize(order.getUser());
                    Hibernate.initialize(order.getItems());
                    return p;
                })
                .orElse(null));
    }

    @Async
    @EventListener
    public void handleOrderCreationEvent(Order order){
        emailService.notifyAdminOnSuccessfulOrderCreation(order);
    }
}

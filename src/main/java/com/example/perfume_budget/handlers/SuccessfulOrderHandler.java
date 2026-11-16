package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
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
    private final OrderRepository orderRepository;
    private final TransactionTemplate readTx;
    private final MailDispatcher mailDispatcher;

    public SuccessfulOrderHandler(EmailService emailService, NotificationService notificationService,
                                  PaymentRepository paymentRepository, OrderRepository orderRepository,
                                  PlatformTransactionManager txManager, MailDispatcher mailDispatcher) {
        this.emailService = emailService;
        this.notificationService = notificationService;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.mailDispatcher = mailDispatcher;
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
     *
     * <p>EVT-01: the re-read and the sends run on the dedicated mail executor ({@link MailDispatcher}); a receipt
     * lost there (executor rejected it, re-read / render / send threw) is logged with the payment reference.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handlePaymentSuccess(Payment payment) {
        String reference = MailDispatcher.reference(payment);
        mailDispatcher.submit("payment receipt / admin e-mail / admin notification", reference, () -> {
            log.info("Payment Successful Event received for {}", reference);
            Payment committed = reload(payment);
            if (committed == null) {
                log.warn("Settled {} not found after commit; no receipt sent", reference);
                return;
            }
            emailService.sendAdminOrderNotification(committed);
            emailService.sendCustomerReceipt(committed);
            notificationService.notifyAdminsOfSuccessfulPayment(committed);
        });
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

    /**
     * The admin "new order" e-mail of a checkout. EVT-01: AFTER COMMIT only (it used to be a plain listener inside the
     * checkout transaction, so a checkout rolled back after publishing, e.g. failing at commit, still e-mailed the
     * admin about an order that does not exist). The order is re-read in the committed state (with its customer and
     * items) in a read-only transaction on the mail executor; outside a transaction it runs at once.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderCreationEvent(Order order){
        String reference = MailDispatcher.reference(order);
        mailDispatcher.submit("admin new-order e-mail", reference, () -> {
            Order committed = reload(order);
            if (committed == null) {
                log.warn("New {} not found after commit; no admin e-mail sent", reference);
                return;
            }
            emailService.notifyAdminOnSuccessfulOrderCreation(committed);
        });
    }

    private Order reload(Order order) {
        if (order.getId() == null) {
            return order;
        }
        return readTx.execute(status -> orderRepository.findById(order.getId())
                .map(o -> {
                    Hibernate.initialize(o.getUser());
                    Hibernate.initialize(o.getItems());
                    return o;
                })
                .orElse(null));
    }
}

package com.example.perfume_budget.outbox;

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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * EVT-01b (L2): performs one outbox row on a mail / notification worker: claim it, re-read the committed payment or
 * order in a read-only transaction of its own, run the side effect (no transaction and no connection held during a
 * send), then mark it DONE or record the failure (retry with back-off, FAILED after
 * {@link OutboxStore#MAX_ATTEMPTS}).
 *
 * <p>Delivery is at least once: a worker that dies after the send but before {@link OutboxStore#complete} leaves the
 * row to be retried after the lease, so that e-mail can go out twice. The admin notification is idempotent on its own
 * ({@code NotificationService} skips an order that already has its payment notification).
 */
@Slf4j
@Component
public class OutboxProcessor {

    private final OutboxStore store;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final TransactionTemplate readTx;

    public OutboxProcessor(OutboxStore store, EmailService emailService, NotificationService notificationService,
                           PaymentRepository paymentRepository, OrderRepository orderRepository,
                           PlatformTransactionManager txManager) {
        this.store = store;
        this.emailService = emailService;
        this.notificationService = notificationService;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The immediate hand-off after the commit: runs the row if nobody has attempted it yet. */
    public void runFresh(OutboxKind kind, String reference) {
        run(() -> store.claimFresh(kind, reference), kind.describe(reference));
    }

    /** A row the drainer reserved: runs it if nobody attempted it since. */
    public void runReserved(OutboxStore.Row reserved) {
        run(() -> store.claim(reserved), reserved.describe());
    }

    private void run(java.util.function.Supplier<Optional<OutboxStore.Row>> claimer, String description) {
        Optional<OutboxStore.Row> claimed;
        try {
            claimed = claimer.get();
        } catch (RuntimeException e) {
            log.warn("Could not claim {} ({}); the outbox retries it", description, e.getClass().getName());
            return;
        }
        claimed.ifPresent(this::execute);
    }

    void execute(OutboxStore.Row row) {
        try {
            perform(row);
        } catch (RuntimeException | Error e) {
            String error = e.getClass().getName(); // never the message: a transport error can quote the recipient
            boolean retryable = OutboxRetryPolicy.retryable(e);
            try {
                switch (store.fail(row, error, retryable)) {
                    case FAILED -> {
                        if (retryable) {
                            log.error("{} for {} was NOT sent: gave up after {} attempts ({}); outbox row marked FAILED",
                                    row.kind().what(), row.describe(), row.attempts(), error);
                        } else {
                            log.error("{} for {} was NOT sent: gave up at attempt {}, the error is not retryable ({});"
                                    + " outbox row marked FAILED", row.kind().what(), row.describe(), row.attempts(), error);
                        }
                    }
                    case RETRY -> log.warn("{} for {} failed (attempt {} of {}, {}); the outbox retries it", row.kind().what(),
                            row.describe(), row.attempts(), OutboxStore.MAX_ATTEMPTS, error);
                    case STALE -> log.warn("{} for {} failed ({}) after its claim (attempt {}) went stale: another worker"
                            + " owns the row now, left to it", row.kind().what(), row.describe(), error, row.attempts());
                }
            } catch (RuntimeException storeDown) {
                log.error("{} for {} failed ({}) and the failure could not be recorded ({}); retried after the lease",
                        row.kind().what(), row.describe(), error, storeDown.getClass().getName());
            }
            return;
        }
        try {
            if (!store.complete(row)) {
                log.warn("{} for {} was done, but its claim (attempt {}) went stale: another worker owns the row now,"
                        + " which may repeat it", row.kind().what(), row.describe(), row.attempts());
            }
        } catch (RuntimeException e) {
            log.warn("{} for {} was done but not marked DONE ({}); it may be repeated after the lease",
                    row.kind().what(), row.describe(), e.getClass().getName());
        }
    }

    private void perform(OutboxStore.Row row) {
        long id = Long.parseLong(row.reference());
        switch (row.kind()) {
            case PAYMENT_RECEIPT -> settled(id, row).ifPresent(emailService::sendCustomerReceipt);
            case PAYMENT_ADMIN_MAIL -> settled(id, row).ifPresent(emailService::sendAdminOrderNotification);
            case PAYMENT_ADMIN_NOTIFICATION -> settled(id, row).ifPresent(notificationService::notifyAdminsOfSuccessfulPayment);
            case NEW_ORDER_ADMIN_MAIL -> order(id, row).ifPresent(emailService::notifyAdminOnSuccessfulOrderCreation);
        }
    }

    /** The committed, settled payment with its order, customer and items; empty (nothing to send) if not COMPLETED. */
    private Optional<Payment> settled(long paymentId, OutboxStore.Row row) {
        Payment payment = readTx.execute(status -> paymentRepository.findById(paymentId)
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(p -> {
                    Order order = p.getOrder();
                    Hibernate.initialize(order);
                    Hibernate.initialize(order.getUser());
                    Hibernate.initialize(order.getItems());
                    return p;
                })
                .orElse(null));
        if (payment == null) {
            log.warn("Settled {} not found; no {} sent", row.describe(), row.kind().what());
        }
        return Optional.ofNullable(payment);
    }

    private Optional<Order> order(long orderId, OutboxStore.Row row) {
        Order order = readTx.execute(status -> orderRepository.findById(orderId)
                .map(o -> {
                    Hibernate.initialize(o.getUser());
                    Hibernate.initialize(o.getItems());
                    return o;
                })
                .orElse(null));
        if (order == null) {
            log.warn("{} not found; no {} sent", row.describe(), row.kind().what());
        }
        return Optional.ofNullable(order);
    }
}

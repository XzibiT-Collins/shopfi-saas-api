package com.example.perfume_budget.handlers;

import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.outbox.OutboxKind;
import com.example.perfume_budget.outbox.OutboxProcessor;
import com.example.perfume_budget.outbox.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Receipt, admin e-mail and admin in-app notification of a settled payment; admin e-mail of a new order.
 *
 * <p>API-04 rework 3 / EVT-01: nothing is sent for work that rolls back. The payment is settled inside a transaction
 * the webhook / verify retry may run several times; each attempt publishes the event, and only the attempt that
 * commits counts.
 *
 * <p>EVT-01b (L2): each side effect is a row in the tenant's side-effect outbox, written by
 * {@link #recordPaymentSideEffects} / {@link #recordNewOrderMail} INSIDE the publishing transaction (just before it
 * commits; it commits or rolls back with the payment / order). After the commit, {@link #handlePaymentSuccess} /
 * {@link #handleOrderCreationEvent} hand each row to its executor without waiting ({@link MailDispatcher#handOff});
 * the worker claims the row, re-reads the committed state and sends ({@link OutboxProcessor}). A refused hand-off,
 * a crash or a failed send leaves the row for {@code OutboxDrainer} (bounded retries), instead of a lost e-mail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuccessfulOrderHandler {
    private static final OutboxKind[] PAYMENT_SIDE_EFFECTS = {
            OutboxKind.PAYMENT_ADMIN_NOTIFICATION, OutboxKind.PAYMENT_ADMIN_MAIL, OutboxKind.PAYMENT_RECEIPT};

    private final OutboxStore outboxStore;
    private final OutboxProcessor outboxProcessor;
    private final MailDispatcher mailDispatcher;

    /**
     * In the settlement transaction, before it commits (first of the before-commit hooks: an INSERT into the outbox
     * table that locks no existing row and flushes nothing, so the coupon row is still the last lock taken).
     */
    @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void recordPaymentSideEffects(Payment payment) {
        for (OutboxKind kind : PAYMENT_SIDE_EFFECTS) {
            outboxStore.record(kind, payment.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handlePaymentSuccess(Payment payment) {
        String id = String.valueOf(payment.getId());
        for (OutboxKind kind : PAYMENT_SIDE_EFFECTS) {
            mailDispatcher.handOff(kind, kind.describe(id), () -> outboxProcessor.runFresh(kind, id));
        }
    }

    @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void recordNewOrderMail(Order order) {
        outboxStore.record(OutboxKind.NEW_ORDER_ADMIN_MAIL, order.getId());
    }

    /**
     * The admin "new order" e-mail of a checkout. EVT-01: AFTER COMMIT only (a checkout rolled back after publishing
     * never e-mails the admin about an order that does not exist).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderCreationEvent(Order order) {
        String id = String.valueOf(order.getId());
        mailDispatcher.handOff(OutboxKind.NEW_ORDER_ADMIN_MAIL, OutboxKind.NEW_ORDER_ADMIN_MAIL.describe(id),
                () -> outboxProcessor.runFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, id));
    }
}

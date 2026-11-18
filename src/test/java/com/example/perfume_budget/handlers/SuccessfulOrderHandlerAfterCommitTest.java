package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * API-04 rework 3: the receipt, the admin e-mail and the admin notification of a settled payment go out only when
 * the settling transaction commits, once per committed transaction, never for an attempt that rolled back.
 */
class SuccessfulOrderHandlerAfterCommitTest {

    /** Transaction manager with real synchronisation callbacks but no resource. */
    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private AnnotationConfigApplicationContext ctx;
    private EmailService email;
    private NotificationService notifications;
    private PaymentRepository payments;
    private OrderRepository orders;
    private TransactionTemplate tx;
    private InMemoryOutboxStore outbox;

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        notifications = mock(NotificationService.class);
        payments = mock(PaymentRepository.class);
        orders = mock(OrderRepository.class);
        PlatformTransactionManager tm = new NoopTxManager();
        outbox = new InMemoryOutboxStore();
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(com.example.perfume_budget.outbox.OutboxStore.class, () -> outbox);
        ctx.registerBean(EmailService.class, () -> email);
        ctx.registerBean(NotificationService.class, () -> notifications);
        ctx.registerBean(PaymentRepository.class, () -> payments);
        ctx.registerBean(OrderRepository.class, () -> orders);
        // the mail executor runs the work inline here, so "sent" is observable right after the commit
        ctx.registerBean(MailDispatcher.class, () -> new MailDispatcher(Runnable::run));
        ctx.registerBean(PlatformTransactionManager.class, () -> tm);
        ctx.registerBean(com.example.perfume_budget.outbox.OutboxProcessor.class);
        ctx.registerBean(SuccessfulOrderHandler.class);
        ctx.registerBean(com.example.perfume_budget.tenancy.TenantRunner.class, () -> {
            com.example.perfume_budget.tenancy.TenantRunner r = mock(com.example.perfume_budget.tenancy.TenantRunner.class);
            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(r).forEachActiveTenant(any());
            return r;
        });
        ctx.registerBean(com.example.perfume_budget.outbox.OutboxDrainer.class);
        ctx.registerBean("txListenerFactory", TransactionalEventListenerFactory.class);
        ctx.refresh();
        tx = new TransactionTemplate(tm);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private Payment payment(Long id, PaymentStatus status) {
        Order order = new Order();
        order.setOrderNumber("PB-1");
        order.setUser(new User());
        Payment p = new Payment();
        p.setId(id);
        p.setStatus(status);
        p.setOrder(order);
        return p;
    }

    private void verifyAllSent(int times, Payment expected) {
        verify(email, times(times)).sendAdminOrderNotification(expected);
        verify(email, times(times)).sendCustomerReceipt(expected);
        verify(notifications, times(times)).notifyAdminsOfSuccessfulPayment(expected);
    }

    @Test
    void rolledBackAttemptSendsNothing() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(p);
            st.setRollbackOnly();
        });
        verifyNoInteractions(email, notifications);
    }

    @Test
    void attemptThatFailsAtCommitSendsNothing() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        try {
            tx.executeWithoutResult(st -> {
                ctx.publishEvent(p);
                throw new IllegalStateException("COUPON_BUSY at commit");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        verifyNoInteractions(email, notifications);
    }

    @Test
    void twoFailedAttemptsThenACommitSendExactlyOnceAfterTheCommit() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(st -> {
                    ctx.publishEvent(p);
                    throw new IllegalStateException("deadlock");
                });
            } catch (IllegalStateException expected) {
                // retried
            }
        }
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(p);
            verifyNoInteractions(email, notifications); // not before the commit
        });
        verifyAllSent(1, p);
    }

    @Test
    void sendsTheCommittedStateReReadAfterCommit() {
        Payment published = payment(7L, PaymentStatus.COMPLETED);
        Payment committed = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(committed));
        tx.executeWithoutResult(st -> ctx.publishEvent(published));
        verifyAllSent(1, committed);
        verify(email, never()).sendCustomerReceipt(published);
    }

    @Test
    void paymentNotCompletedAfterCommitSendsNothing() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(payment(7L, PaymentStatus.CANCELLED)));
        tx.executeWithoutResult(st -> ctx.publishEvent(p));
        verifyNoInteractions(email, notifications);
    }

    @Test
    void outsideATransactionItStillSendsImmediately() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        ctx.publishEvent(p);
        verifyAllSent(1, p);
        verify(email, never()).notifyAdminOnSuccessfulOrderCreation(any());
    }

    // ---- EVT-01 (a): the admin "new order" e-mail of a checkout

    private Order order(Long id, String number) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNumber(number);
        o.setUser(new User());
        return o;
    }

    @Test
    void newOrderMailOfARolledBackCheckoutIsNeverSent() {
        Order o = order(11L, "PB-11");
        when(orders.findById(11L)).thenReturn(Optional.of(o));
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(o);
            st.setRollbackOnly();
        });
        verifyNoInteractions(email, notifications);
    }

    @Test
    void newOrderMailOfACheckoutFailingAtCommitIsNeverSent() {
        Order o = order(11L, "PB-11");
        when(orders.findById(11L)).thenReturn(Optional.of(o));
        try {
            tx.executeWithoutResult(st -> {
                ctx.publishEvent(o);
                throw new IllegalStateException("COUPON_BUSY at commit");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        verifyNoInteractions(email, notifications);
    }

    @Test
    void newOrderMailIsSentOnceAfterTheCommitThatFollowsTwoFailedAttempts() {
        Order published = order(11L, "PB-11");
        Order committed = order(11L, "PB-11");
        when(orders.findById(11L)).thenReturn(Optional.of(committed));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(st -> {
                    ctx.publishEvent(published);
                    throw new IllegalStateException("deadlock");
                });
            } catch (IllegalStateException expected) {
                // retried
            }
        }
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(published);
            verifyNoInteractions(email); // not before the commit
        });
        verify(email, times(1)).notifyAdminOnSuccessfulOrderCreation(committed);
        verify(email, never()).notifyAdminOnSuccessfulOrderCreation(published);
        verifyNoInteractions(notifications);
    }

    @Test
    void newOrderMissingAfterCommitSendsNothing() {
        when(orders.findById(11L)).thenReturn(Optional.empty());
        tx.executeWithoutResult(st -> ctx.publishEvent(order(11L, "PB-11")));
        verify(email, never()).notifyAdminOnSuccessfulOrderCreation(any());
    }

    @Test
    void newOrderOutsideATransactionIsSentAtOnce() {
        Order o = order(11L, "PB-11");
        when(orders.findById(11L)).thenReturn(Optional.of(o));
        ctx.publishEvent(o);
        verify(email, times(1)).notifyAdminOnSuccessfulOrderCreation(o);
    }

    // ---- EVT-01 rework 1 (F1)

    @Test
    void theReReadAlwaysRunsInItsOwnTransaction() {
        com.example.perfume_budget.outbox.OutboxProcessor proc = ctx.getBean(com.example.perfume_budget.outbox.OutboxProcessor.class);
        TransactionTemplate readTx = (TransactionTemplate) org.springframework.test.util.ReflectionTestUtils.getField(proc, "readTx");
        assertThat(readTx.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(readTx.isReadOnly()).isTrue();
    }

    @Test
    void theAdminNotificationDoesNotDependOnTheMailExecutor() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(java.util.Optional.of(p));
        java.util.concurrent.Executor fullMail = r -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        SuccessfulOrderHandler h = new SuccessfulOrderHandler(outbox,
                ctx.getBean(com.example.perfume_budget.outbox.OutboxProcessor.class), new MailDispatcher(fullMail, Runnable::run));
        h.recordPaymentSideEffects(p);
        h.handlePaymentSuccess(p);
        verifyNoInteractions(email);
        verify(notifications).notifyAdminsOfSuccessfulPayment(p);
    }

    // ---- EVT-01b (L2): side-effect outbox

    private void drain() {
        ctx.getBean(com.example.perfume_budget.outbox.OutboxDrainer.class).drainAllTenants();
    }

    @Test
    void eachSideEffectIsOneOutboxRowWrittenInTheCommittingTransactionAndDoneAfterwards() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(p);
            ctx.publishEvent(p); // a duplicated event in the same transaction
        });
        assertThat(outbox.rows).hasSize(3);
        assertThat(outbox.rows.values()).allSatisfy(e -> {
            assertThat(e.status).isEqualTo("DONE");
            assertThat(e.attempts).isEqualTo(1);
        });
        verifyAllSent(1, p);
    }

    @Test
    void aRolledBackAttemptLeavesNoOutboxRow() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(p);
            st.setRollbackOnly();
        });
        assertThat(outbox.rows).isEmpty();
        drain();
        verifyNoInteractions(email, notifications);
    }

    @Test
    void aRefusedHandOffIsSentByTheDrainExactlyOnce() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.Executor mail = r -> {
            if (full.get()) throw new java.util.concurrent.RejectedExecutionException("queue full");
            r.run();
        };
        SuccessfulOrderHandler h = new SuccessfulOrderHandler(outbox,
                ctx.getBean(com.example.perfume_budget.outbox.OutboxProcessor.class), new MailDispatcher(mail, mail));
        com.example.perfume_budget.outbox.OutboxDrainer drainer = new com.example.perfume_budget.outbox.OutboxDrainer(outbox,
                ctx.getBean(com.example.perfume_budget.outbox.OutboxProcessor.class), new MailDispatcher(mail, mail),
                ctx.getBean(com.example.perfume_budget.tenancy.TenantRunner.class));
        tx.executeWithoutResult(st -> {
            h.recordPaymentSideEffects(p);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { h.handlePaymentSuccess(p); }
                    });
        });
        verifyNoInteractions(email, notifications);
        outbox.makeDue();
        assertThat(drainer.drainCurrentTenant()).as("still full: nothing handed off").isZero();
        assertThat(outbox.rows.values()).allSatisfy(e -> assertThat(e.status).isEqualTo("PENDING"));
        full.set(false);
        assertThat(drainer.drainCurrentTenant()).isEqualTo(3);
        verifyAllSent(1, p);
        outbox.makeDue();
        drainer.drainCurrentTenant();
        h.handlePaymentSuccess(p); // a late duplicate hand-off
        verifyAllSent(1, p);
    }

    @Test
    void aFailedSendIsRetriedOnlyForThatSideEffectThenGivenUpAfterTheBound() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        doThrow(new com.example.perfume_budget.notification.MailNotSentException("Customer receipt", "payment 7",
                new IllegalStateException())).when(email).sendCustomerReceipt(any());
        tx.executeWithoutResult(st -> ctx.publishEvent(p));
        assertThat(outbox.get(com.example.perfume_budget.outbox.OutboxKind.PAYMENT_RECEIPT, 7L).status).isEqualTo("PENDING");
        for (int i = 1; i < com.example.perfume_budget.outbox.OutboxStore.MAX_ATTEMPTS; i++) {
            outbox.makeDue();
            drain();
        }
        InMemoryOutboxStore.Entry receipt = outbox.get(com.example.perfume_budget.outbox.OutboxKind.PAYMENT_RECEIPT, 7L);
        assertThat(receipt.status).isEqualTo("FAILED");
        assertThat(receipt.attempts).isEqualTo(com.example.perfume_budget.outbox.OutboxStore.MAX_ATTEMPTS);
        assertThat(receipt.lastError).isEqualTo(com.example.perfume_budget.notification.MailNotSentException.class.getName());
        verify(email, times(com.example.perfume_budget.outbox.OutboxStore.MAX_ATTEMPTS)).sendCustomerReceipt(p);
        verify(email, times(1)).sendAdminOrderNotification(p);
        verify(notifications, times(1)).notifyAdminsOfSuccessfulPayment(p);
        outbox.makeDue();
        drain();
        verify(email, times(com.example.perfume_budget.outbox.OutboxStore.MAX_ATTEMPTS)).sendCustomerReceipt(p);
    }

    /** EVT-01c (5): a receipt that cannot be rendered (no default address: NPE) goes FAILED at once, not after 8 tries. */
    @Test
    void aNonRetryableReceiptFailureIsFailedAtTheFirstAttempt_theOtherSideEffectsAreDone() {
        Payment p = payment(7L, PaymentStatus.COMPLETED);
        when(payments.findById(7L)).thenReturn(Optional.of(p));
        doThrow(new com.example.perfume_budget.notification.MailNotSentException("Customer receipt", "payment 7",
                new NullPointerException())).when(email).sendCustomerReceipt(any());
        tx.executeWithoutResult(st -> ctx.publishEvent(p));
        InMemoryOutboxStore.Entry receipt = outbox.get(com.example.perfume_budget.outbox.OutboxKind.PAYMENT_RECEIPT, 7L);
        assertThat(receipt.status).isEqualTo("FAILED");
        assertThat(receipt.attempts).isEqualTo(1);
        assertThat(outbox.get(com.example.perfume_budget.outbox.OutboxKind.PAYMENT_ADMIN_MAIL, 7L).status).isEqualTo("DONE");
        outbox.makeDue();
        drain();
        verify(email, times(1)).sendCustomerReceipt(p);
    }
}

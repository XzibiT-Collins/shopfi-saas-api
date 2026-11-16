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

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        notifications = mock(NotificationService.class);
        payments = mock(PaymentRepository.class);
        orders = mock(OrderRepository.class);
        PlatformTransactionManager tm = new NoopTxManager();
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(EmailService.class, () -> email);
        ctx.registerBean(NotificationService.class, () -> notifications);
        ctx.registerBean(PaymentRepository.class, () -> payments);
        ctx.registerBean(OrderRepository.class, () -> orders);
        // the mail executor runs the work inline here, so "sent" is observable right after the commit
        ctx.registerBean(MailDispatcher.class, () -> new MailDispatcher(Runnable::run));
        ctx.registerBean(PlatformTransactionManager.class, () -> tm);
        ctx.registerBean(SuccessfulOrderHandler.class);
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
}

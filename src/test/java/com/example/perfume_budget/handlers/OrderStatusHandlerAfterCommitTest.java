package com.example.perfume_budget.handlers;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.notification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;

/** ORD-01: the customer status e-mail goes out only when the surrounding transaction commits. */
class OrderStatusHandlerAfterCommitTest {

    /** Transaction manager with real synchronisation callbacks but no resource. */
    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private AnnotationConfigApplicationContext ctx;
    private EmailService email;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(EmailService.class, () -> email);
        ctx.registerBean(OrderStatusHandler.class);
        ctx.registerBean("txListenerFactory", TransactionalEventListenerFactory.class);
        ctx.refresh();
        PlatformTransactionManager tm = new NoopTxManager();
        tx = new TransactionTemplate(tm);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static OrderStatusChangeEvent event() {
        return OrderStatusChangeEvent.builder().orderNumber("PB-1").customerEmail("c@shop.test")
                .status(OrderProcessingStatus.PACKING).build();
    }

    @Test
    void rolledBackTransactionSendsNoEmail() {
        tx.executeWithoutResult((TransactionStatus st) -> {
            ctx.publishEvent(event());
            st.setRollbackOnly();
        });
        verifyNoInteractions(email);
    }

    @Test
    void exceptionInTransactionSendsNoEmail() {
        try {
            tx.executeWithoutResult(st -> {
                ctx.publishEvent(event());
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        verifyNoInteractions(email);
    }

    @Test
    void committedTransactionSendsExactlyOneEmailAfterCommit() {
        tx.executeWithoutResult(st -> {
            ctx.publishEvent(event());
            verifyNoInteractions(email); // not before the commit
        });
        verify(email, times(1)).notifyCustomerOnOrderStatusChange(any());
    }

    @Test
    void outsideATransactionItStillSendsImmediately() {
        ctx.publishEvent(event());
        verify(email, times(1)).notifyCustomerOnOrderStatusChange(any());
    }
}

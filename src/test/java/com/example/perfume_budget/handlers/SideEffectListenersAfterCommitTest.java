package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * EVT-01 (a): every e-mail / analytics listener reacts only to work that COMMITTED, once per committed transaction:
 * a rolled-back (or failed-at-commit) attempt sends nothing and counts nothing, a retried transaction (two failed
 * attempts, then a commit) has its effect exactly once, and a publication outside a transaction still runs at once.
 */
class SideEffectListenersAfterCommitTest {

    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private AnnotationConfigApplicationContext ctx;
    private EmailService email;
    private ProductRepository products;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        products = mock(ProductRepository.class);
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(EmailService.class, () -> email);
        ctx.registerBean(ProductRepository.class, () -> products);
        ctx.registerBean(MailDispatcher.class, () -> new MailDispatcher(Runnable::run));
        ctx.registerBean(OTPEmailSendHandler.class);
        ctx.registerBean(ForgotPasswordHandler.class);
        ctx.registerBean(ProductViewCountHandler.class);
        ctx.registerBean("txListenerFactory", TransactionalEventListenerFactory.class);
        ctx.refresh();
        tx = new TransactionTemplate(new NoopTxManager());
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static List<Object> events() {
        return List.of(new UserRegistrationEvent("u@shop.test", "123456"), new LoginOtpEvent("u@shop.test", "654321"),
                new ResetPasswordEvent("u@shop.test", "U", "reset-token", "acme"), new ViewCountEvent(5L),
                new CartTimesEvent(6L));
    }

    private void verifyEachSent(int times) {
        verify(email, times(times)).sendOtpEmail("u@shop.test", "123456");
        verify(email, times(times)).sendOtpEmail("u@shop.test", "654321");
        verify(email, times(times)).sendForgotPasswordEmail("u@shop.test", "U", "reset-token", "acme");
        verify(products, times(times)).incrementViewCount(5L);
        verify(products, times(times)).incrementCartTimesCount(6L);
    }

    @Test
    void rolledBackTransactionSendsAndCountsNothing() {
        tx.executeWithoutResult(st -> {
            events().forEach(ctx::publishEvent);
            st.setRollbackOnly();
        });
        verifyNoInteractions(email, products);
    }

    @Test
    void transactionFailingAtCommitSendsAndCountsNothing() {
        try {
            tx.executeWithoutResult(st -> {
                events().forEach(ctx::publishEvent);
                throw new IllegalStateException("deadlock");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        verifyNoInteractions(email, products);
    }

    @Test
    void retriedTransactionHasItsEffectsOnceAfterTheCommit() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(st -> {
                    events().forEach(ctx::publishEvent);
                    throw new IllegalStateException("deadlock");
                });
            } catch (IllegalStateException expected) {
                // retried
            }
        }
        tx.executeWithoutResult(st -> {
            events().forEach(ctx::publishEvent);
            verifyNoInteractions(email, products); // not before the commit
        });
        verifyEachSent(1);
    }

    @Test
    void outsideATransactionEverythingRunsAtOnce() {
        events().forEach(ctx::publishEvent);
        verifyEachSent(1);
    }
}

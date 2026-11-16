package com.example.perfume_budget.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EVT-01 (d): e-mail / receipt work has its own bounded executor (not the shared default one), and a burst that
 * fills it does not drop work: the submitting thread runs the overflow itself (keeping its own tenant context), and
 * only a shut-down executor rejects (the caller then logs the reference). Uncaught exceptions of the remaining
 * {@code @Async} methods are logged with the reference of their arguments.
 */
class AsyncConfigMailExecutorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void stop() {
        if (executor != null) executor.shutdown();
        TenantContext.clear();
    }

    @Test
    void mailExecutorIsDedicatedBoundedAndTenantAware() {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().mailTaskExecutor();
        assertThat(executor.getThreadNamePrefix()).isEqualTo("mail-");
        assertThat(executor.getCorePoolSize()).isEqualTo(AsyncConfig.MAIL_THREADS);
        assertThat(executor.getMaxPoolSize()).isEqualTo(AsyncConfig.MAIL_THREADS);
        assertThat(executor.getQueueCapacity()).isEqualTo(AsyncConfig.MAIL_QUEUE_CAPACITY).isBetween(100, 5_000);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(AsyncConfig.RunInCallerUnlessShutDown.class);
    }

    @Test
    void aFullMailExecutorRunsTheOverflowOnTheCallerAndKeepsTheCallersTenant() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> { started.countDown(); await(release); }); // occupies the only thread
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });                                         // fills the queue
        TenantIdentity acme = TenantIdentity.tenant("acme", "tenant_acme");
        TenantContext.set(acme);
        AtomicReference<String> ranOn = new AtomicReference<>();
        AtomicReference<String> tenantInWork = new AtomicReference<>();
        executor.execute(() -> {                                             // overflow: not dropped
            ranOn.set(Thread.currentThread().getName());
            tenantInWork.set(TenantContext.getSlug());
        });
        assertThat(ranOn.get()).as("ran at once on the submitting thread").isEqualTo(Thread.currentThread().getName());
        assertThat(tenantInWork.get()).isEqualTo("acme");
        assertThat(TenantContext.get()).as("the caller keeps its tenant").isEqualTo(acme);
        release.countDown();
    }

    @Test
    void aShutDownMailExecutorRejects() {
        executor = AsyncConfig.mailExecutor(1, 1);
        executor.shutdown();
        AtomicInteger ran = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(ran::incrementAndGet)).isInstanceOf(RejectedExecutionException.class);
        assertThat(ran).hasValue(0);
    }

    @Test
    void uncaughtAsyncExceptionsAreLoggedWithTheReferenceOnly() throws Exception {
        AsyncUncaughtExceptionHandler handler = new AsyncConfig().getAsyncUncaughtExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Payment p = new Payment();
            p.setId(7L);
            p.setProviderPaymentReference("T-7");
            p.setCustomerEmail("buyer@shop.test");
            handler.handleUncaughtException(new IllegalStateException("smtp said no to buyer@shop.test"),
                    AsyncConfigMailExecutorTest.class.getDeclaredMethod("stop"), p);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("payment 7 (ref T-7)").contains("IllegalStateException")
                    .contains("stop").doesNotContain("buyer@shop.test");
        });
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

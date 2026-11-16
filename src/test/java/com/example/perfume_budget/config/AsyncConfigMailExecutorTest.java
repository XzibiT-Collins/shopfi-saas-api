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
 * EVT-01 (d): e-mail / receipt work has its own bounded executor (not the shared default one). Rework 1: a burst
 * that fills it is NEVER run on the submitting thread (which is inside its transaction's after-commit callback): the
 * submitter waits a bounded time for a slot, then the task is rejected (the caller logs the reference). Uncaught exceptions of the remaining
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
        assertThat(((TrackedTaskExecutor) executor).getRejectionPolicy())
                .isInstanceOf(AsyncConfig.WaitBrieflyThenReject.class);
    }

    @Test
    void mailWorkRunsOnAMailThreadWithTheSubmittersTenantAndLeavesNoTenantBehind() throws Exception {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().mailTaskExecutor();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            seen.set(Thread.currentThread().getName() + "/" + TenantContext.getSlug());
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).startsWith("mail-").endsWith("/acme");
        TenantContext.clear();
        AtomicReference<Object> leaked = new AtomicReference<>("unset");
        CountDownLatch done2 = new CountDownLatch(1);
        executor.execute(() -> {
            leaked.set(TenantContext.get());
            done2.countDown();
        });
        assertThat(done2.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(leaked.get()).as("a pooled mail thread does not keep the previous tenant").isNull();
    }

    @Test
    void aFullMailExecutorNeverRunsTheOverflowOnTheCallerItWaitsBrieflyThenRejects() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 1, 300);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> { started.countDown(); await(release); }); // occupies the only thread
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });                                         // fills the queue
        AtomicInteger ran = new AtomicInteger();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(ran::incrementAndGet)).isInstanceOf(RejectedExecutionException.class);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(waitedMs).as("bounded wait").isBetween(250L, 3_000L);
        assertThat(ran).as("never run on the submitting thread").hasValue(0);
        release.countDown();
        assertThat(((TrackedTaskExecutor) executor).awaitIdle(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(ran).as("and never later either: it was dropped").hasValue(0);
    }

    @Test
    void aSlotThatFreesUpWithinTheWaitTakesTheTaskOnAMailThread() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 1, 5_000);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });
        new Thread(() -> { sleep(300); release.countDown(); }).start();
        AtomicReference<String> ranOn = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> { ranOn.set(Thread.currentThread().getName()); done.countDown(); });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ranOn.get()).startsWith("mail-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void productionPolicyWaitsAtMostTheDocumentedBound() {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().mailTaskExecutor();
        assertThat(((TrackedTaskExecutor) executor).getRejectionPolicy())
                .isInstanceOf(AsyncConfig.WaitBrieflyThenReject.class);
        assertThat(AsyncConfig.MAIL_SUBMIT_WAIT_MILLIS).isBetween(100L, 2_000L);
        ThreadPoolTaskExecutor notify = (ThreadPoolTaskExecutor) new AsyncConfig().notificationTaskExecutor();
        try {
            assertThat(notify.getThreadNamePrefix()).isEqualTo("notify-");
            assertThat(((TrackedTaskExecutor) notify).getRejectionPolicy())
                    .isInstanceOf(AsyncConfig.WaitBrieflyThenReject.class);
        } finally {
            notify.shutdown();
        }
    }

    /** F4 R19: shutting down still sends what is queued (bounded by awaitTermination), it does not discard it. */
    @Test
    void shutdownSendsTheQueuedMailInsteadOfDiscardingIt() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 10, 100);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        executor.execute(() -> { started.countDown(); sleep(300); sent.incrementAndGet(); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(sent::incrementAndGet); // queued behind the running one
        executor.shutdown();
        assertThat(sent).as("the running AND the queued mail went out before shutdown returned").hasValue(2);
    }

    /** F5: in-flight counts a task from submission to its end, including the dequeued-not-yet-running window. */
    @Test
    void trackedExecutorCountsSubmittedUntilFinishedAndRejectedTasksStopCounting() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 1, 50);
        TrackedTaskExecutor tracked = (TrackedTaskExecutor) executor;
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> await(release));
        executor.execute(() -> { });
        assertThat(tracked.inFlight()).isEqualTo(2);
        assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        assertThat(tracked.inFlight()).as("a rejected task is not in flight").isEqualTo(2);
        assertThat(tracked.awaitIdle(java.time.Duration.ofMillis(200))).as("not idle while work is pending").isFalse();
        release.countDown();
        assertThat(tracked.awaitIdle(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(tracked.inFlight()).isZero();
        AtomicInteger failing = new AtomicInteger();
        executor.execute(() -> { failing.incrementAndGet(); throw new IllegalStateException("boom"); });
        assertThat(tracked.awaitIdle(java.time.Duration.ofSeconds(5))).as("a failing task also ends").isTrue();
        assertThat(failing).hasValue(1);
    }

    @Test
    void aShutDownMailExecutorRejects() {
        executor = AsyncConfig.mailExecutor(1, 1, 100);
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

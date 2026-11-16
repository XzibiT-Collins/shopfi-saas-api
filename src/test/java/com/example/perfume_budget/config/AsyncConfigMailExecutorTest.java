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
 * submitter is rejected at once (EVT-01b: no wait while its connection is held; the outbox re-drives the work). Uncaught exceptions of the remaining
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
                .isInstanceOf(AsyncConfig.RejectWhenFull.class);
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

    /**
     * EVT-01b L1: the submitter is a request / webhook thread inside its transaction's after-commit callback, which
     * still holds its JDBC connection (Spring releases it only after the callbacks). So a full executor rejects AT
     * ONCE: the policy never calls a blocking queue method (no wait for a slot while holding a connection), and never
     * runs the task on the caller. The outbox (L2) re-drives the rejected work later.
     */
    @Test
    void theSaturationPolicyRejectsAtOnceItNeverWaitsForASlotNorRunsTheTaskOnTheCaller() {
        java.util.List<String> blockingCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.ArrayBlockingQueue<Runnable> queue = new java.util.concurrent.ArrayBlockingQueue<>(1) {
            @Override
            public boolean offer(Runnable r, long timeout, TimeUnit unit) throws InterruptedException {
                blockingCalls.add("offer(" + timeout + " " + unit + ")");
                return super.offer(r, timeout, unit);
            }

            @Override
            public void put(Runnable r) throws InterruptedException {
                blockingCalls.add("put");
                super.put(r);
            }
        };
        queue.add(() -> { });
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1,
                TimeUnit.SECONDS, queue);
        try {
            AtomicInteger ran = new AtomicInteger();
            assertThatThrownBy(() -> new AsyncConfig.RejectWhenFull().rejectedExecution(ran::incrementAndGet, pool))
                    .isInstanceOf(RejectedExecutionException.class).hasMessageContaining("queue full");
            assertThat(blockingCalls).as("never waits for a queue slot").isEmpty();
            assertThat(ran).as("never runs on the caller").hasValue(0);
            pool.shutdown();
            assertThatThrownBy(() -> new AsyncConfig.RejectWhenFull().rejectedExecution(ran::incrementAndGet, pool))
                    .isInstanceOf(RejectedExecutionException.class).hasMessageContaining("shut down");
            assertThat(blockingCalls).isEmpty();
            assertThat(ran).hasValue(0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFullMailExecutorRejectsTheOverflowWhichNeverRunsLater() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> { started.countDown(); await(release); }); // occupies the only thread
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });                                         // fills the queue
        AtomicInteger ran = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(ran::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class)
                .satisfies(e -> assertThat(e.getCause()).hasMessageContaining("queue full"));
        assertThat(ran).as("never run on the submitting thread").hasValue(0);
        release.countDown();
        assertThat(((TrackedTaskExecutor) executor).awaitIdle(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(ran).as("and never later either: it was rejected").hasValue(0);
    }

    @Test
    void productionPolicyRejectsWithoutWaiting() {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().mailTaskExecutor();
        assertThat(((TrackedTaskExecutor) executor).getRejectionPolicy()).isInstanceOf(AsyncConfig.RejectWhenFull.class);
        ThreadPoolTaskExecutor notify = (ThreadPoolTaskExecutor) new AsyncConfig().notificationTaskExecutor();
        try {
            assertThat(notify.getThreadNamePrefix()).isEqualTo("notify-");
            assertThat(((TrackedTaskExecutor) notify).getRejectionPolicy()).isInstanceOf(AsyncConfig.RejectWhenFull.class);
        } finally {
            notify.shutdown();
        }
    }

    /**
     * EVT-01b L3 (N27): the notification executor runs two notifications at once, so one stuck on a row lock (e.g.
     * a recipient row another transaction holds) does not hold up the next tenant's / order's notification.
     */
    @Test
    void theNotificationExecutorRunsTwoNotificationsAtOnce() throws Exception {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().notificationTaskExecutor();
        assertThat(AsyncConfig.NOTIFICATION_THREADS).isEqualTo(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stuck = new CountDownLatch(1);
        executor.execute(() -> { stuck.countDown(); await(release); });
        assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch second = new CountDownLatch(1);
        executor.execute(second::countDown);
        try {
            assertThat(second.await(5, TimeUnit.SECONDS)).as("ran while the first is still stuck").isTrue();
        } finally {
            release.countDown();
        }
    }

    /** F4 R19: shutting down still sends what is queued (bounded by awaitTermination), it does not discard it. */
    @Test
    void shutdownSendsTheQueuedMailInsteadOfDiscardingIt() throws Exception {
        executor = AsyncConfig.mailExecutor(1, 10);
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
        executor = AsyncConfig.mailExecutor(1, 1);
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

    /**
     * EVT-01b L6: an executor that does NOT wait on shutdown (webhook / default) discards its queued tasks
     * ({@code shutdownNow}); they will never run, so they stop counting as in flight (awaitIdle no longer hangs on
     * them). The running task still counts until it ends.
     */
    @Test
    void tasksDiscardedAtShutdownByANonWaitingExecutorStopCounting() throws Exception {
        TrackedTaskExecutor tracked = new TrackedTaskExecutor();
        tracked.setCorePoolSize(1);
        tracked.setMaxPoolSize(1);
        tracked.setQueueCapacity(10);
        tracked.setWaitForTasksToCompleteOnShutdown(false);
        tracked.initialize();
        executor = tracked;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger discardedRan = new AtomicInteger();
        tracked.execute(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS); // ignores the shutdown interrupt once, like a busy send
            } catch (InterruptedException e) {
                await(release);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 3; i++) {
            tracked.execute(discardedRan::incrementAndGet);
        }
        java.util.concurrent.Future<?> future = tracked.submit(discardedRan::incrementAndGet);
        assertThat(tracked.inFlight()).isEqualTo(5);
        tracked.shutdown();
        assertThat(tracked.inFlight()).as("only the running task is still in flight").isEqualTo(1);
        assertThat(future.isCancelled()).as("a discarded future is cancelled").isTrue();
        release.countDown();
        assertThat(tracked.awaitIdle(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(discardedRan).as("the discarded tasks never ran").hasValue(0);
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

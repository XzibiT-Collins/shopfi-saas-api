package com.example.perfume_budget.checkout;

import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.config.TrackedTaskExecutor;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.outbox.OutboxDrainer;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * EVT-01b L1, the reviewer's probe as a regression test: 25 concurrent checkouts against a FULL mail executor with
 * the test pool of 10 connections. Each checkout's admin new-order e-mail is handed off in its transaction's
 * after-commit callback, where that thread still holds its JDBC connection.
 *
 * <p>Deterministic evidence, not wall-clock: a sampler inspects every thread's stack throughout the burst and counts
 * any thread that is WAITING FOR A QUEUE SLOT (a blocking {@code offer(timeout)} / {@code put} on an executor queue)
 * from inside an after-commit callback, i.e. while holding a connection. Under EVT-01 rework 1 (1 s wait) every
 * checkout spent a second there; now there must be none. The hand-off is refused at once, the order's outbox row is
 * PENDING, and the drain later sends each e-mail exactly once. An unrelated catalog read issued mid-burst succeeds,
 * and the pool is idle (0 active, 0 waiting) after the burst.
 */
class MailHandOffPoolIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final int CHECKOUTS = 25;

    @Autowired @Qualifier(AsyncConfig.MAIL_EXECUTOR) private TaskExecutor mailBean;
    @Autowired private DataSource dataSource;
    @Autowired private OutboxDrainer drainer;

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void freeMail() {
        release.countDown();
    }

    private void saturateMail() throws InterruptedException {
        TrackedTaskExecutor mail = (TrackedTaskExecutor) mailBean;
        CountDownLatch started = new CountDownLatch(mail.getMaxPoolSize());
        for (int i = 0; i < mail.getMaxPoolSize(); i++) {
            mail.execute(() -> {
                started.countDown();
                try {
                    release.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < mail.getQueueCapacity(); i++) {
            mail.execute(() -> { });
        }
        assertThat(mail.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
    }

    /** A thread waiting for an executor queue slot from inside a transaction's after-commit callback. */
    static boolean waitingForASlotAfterCommit(StackTraceElement[] stack) {
        boolean slotWait = false;
        boolean afterCommit = false;
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            String m = f.getMethodName();
            if ((c.endsWith("BlockingQueue") || c.endsWith("BlockingDeque")) && (m.equals("put") || m.equals("offer"))
                    && stackHasPark(stack)) {
                slotWait = true;
            }
            if (c.equals("org.springframework.transaction.support.TransactionSynchronizationUtils")
                    && (m.equals("invokeAfterCommit") || m.equals("invokeAfterCompletion"))) {
                afterCommit = true;
            }
        }
        return slotWait && afterCommit;
    }

    private static boolean stackHasPark(StackTraceElement[] stack) {
        return stack.length > 0 && (stack[0].getClassName().equals("jdk.internal.misc.Unsafe") && stack[0].getMethodName().equals("park"));
    }

    @Test
    void sampler_recognisesAThreadWaitingForASlotInAnAfterCommitCallback() {
        StackTraceElement[] waiting = {
                new StackTraceElement("jdk.internal.misc.Unsafe", "park", null, -2),
                new StackTraceElement("java.util.concurrent.LinkedBlockingQueue", "offer", null, 1),
                new StackTraceElement("com.example.perfume_budget.config.AsyncConfig$X", "rejectedExecution", null, 1),
                new StackTraceElement("org.springframework.transaction.support.TransactionSynchronizationUtils", "invokeAfterCommit", null, 1)};
        assertThat(waitingForASlotAfterCommit(waiting)).isTrue();
        StackTraceElement[] idleWorker = {
                new StackTraceElement("jdk.internal.misc.Unsafe", "park", null, -2),
                new StackTraceElement("java.util.concurrent.LinkedBlockingQueue", "take", null, 1)};
        assertThat(waitingForASlotAfterCommit(idleWorker)).isFalse();
    }

    private int newOrderMails() {
        return (int) Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject() != null && m.subject().startsWith("New Order Received - "))
                .count();
    }

    @Test
    void concurrentCheckoutsAgainstAFullMailQueueNeverWaitForASlotWhileHoldingAConnection() throws Exception {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < CHECKOUTS; i++) {
            Customer c = customer(acme, "pool-" + i + "@acme.test");
            cart(c, product(acme, "P-POOL-" + i, "100.00", 20), 1);
            customers.add(c);
        }
        awaitBackgroundIdle();
        int mailsBefore = newOrderMails();
        saturateMail();

        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger maxWaiting = new AtomicInteger();
        Map<String, String> offenders = new ConcurrentHashMap<>();
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    if (waitingForASlotAfterCommit(e.getValue())) {
                        offenders.put(e.getKey().getName(), String.valueOf(e.getValue()[1]));
                    }
                }
                maxWaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                samples.incrementAndGet();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "slot-wait-sampler");
        sampler.start();

        ExecutorService clients = Executors.newFixedThreadPool(CHECKOUTS + 1);
        try {
            CyclicBarrier go = new CyclicBarrier(CHECKOUTS + 1);
            List<Future<Integer>> results = new ArrayList<>();
            for (Customer c : customers) {
                results.add(clients.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    MvcResult r = checkout(c, null, null);
                    return r.getResponse().getStatus();
                }));
            }
            Future<Integer> catalog = clients.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                Thread.sleep(50); // mid-burst
                return mvc.perform(get(URI.create("/api/v1/product/listing")).header("X-Tenant-ID", acme.slug()))
                        .andReturn().getResponse().getStatus();
            });
            for (Future<Integer> f : results) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
            long t0 = System.nanoTime();
            assertThat(catalog.get(60, TimeUnit.SECONDS)).as("the unrelated catalog read is served").isEqualTo(200);
            System.out.printf("EVT-01b L1 probe: %d samples, max threads awaiting a connection %d, catalog read done %d ms after the burst%n",
                    samples.get(), maxWaiting.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        } finally {
            sampling.set(false);
            sampler.join(5_000);
            clients.shutdownNow();
        }

        assertThat(samples.get()).as("the sampler watched the whole burst").isGreaterThan(5);
        assertThat(offenders).as("threads seen waiting for a mail queue slot inside an after-commit callback").isEmpty();
        assertThat(pool.getActiveConnections()).as("no connection kept after the burst").isZero();
        assertThat(pool.getThreadsAwaitingConnection()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.side_effect_outbox WHERE kind = 'NEW_ORDER_ADMIN_MAIL' "
                + "AND status = 'PENDING' AND attempts = 0", Integer.class)).as("every refused e-mail is in the outbox").isEqualTo(CHECKOUTS);

        release.countDown();
        awaitBackgroundIdle();
        assertThat(newOrderMails() - mailsBefore).as("nothing sent behind the outbox's back").isZero();
        jdbc.update("UPDATE tenant_acme.side_effect_outbox SET next_attempt_at = now() WHERE status = 'PENDING'");
        drainer.drainAllTenants();
        awaitBackgroundIdle();
        drainer.drainAllTenants(); // a second drain finds nothing to do
        awaitBackgroundIdle();
        assertThat(newOrderMails() - mailsBefore).as("each new-order e-mail sent exactly once").isEqualTo(CHECKOUTS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.side_effect_outbox WHERE kind = 'NEW_ORDER_ADMIN_MAIL' "
                + "AND status = 'DONE'", Integer.class)).isEqualTo(CHECKOUTS);
    }
}

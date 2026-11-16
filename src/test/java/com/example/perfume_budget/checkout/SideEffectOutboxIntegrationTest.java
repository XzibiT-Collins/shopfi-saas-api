package com.example.perfume_budget.checkout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.config.TrackedTaskExecutor;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.outbox.OutboxDrainer;
import com.example.perfume_budget.outbox.OutboxKind;
import com.example.perfume_budget.outbox.OutboxProcessor;
import com.example.perfume_budget.outbox.OutboxStore;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * EVT-01b (L2) on the real stack: the side-effect outbox (tenant table {@code side_effect_outbox}, V13).
 * <ul>
 *   <li>the row is part of the publishing transaction: a rollback leaves none, a duplicate record is one row;</li>
 *   <li>crash after commit: a worker that claimed the row and died leaves it to the drain after the lease, which
 *       sends it once;</li>
 *   <li>duplicate prevention: the immediate hand-off and concurrent drains racing for the same row send once;</li>
 *   <li>bounded retries with back-off, then FAILED (poison) with an ERROR naming the row.</li>
 * </ul>
 */
class SideEffectOutboxIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired private OutboxStore store;
    @Autowired private OutboxProcessor processor;
    @Autowired private OutboxDrainer drainer;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier(AsyncConfig.MAIL_EXECUTOR) private TaskExecutor mailBean;

    private final CountDownLatch release = new CountDownLatch(1);
    private ListAppender<ILoggingEvent> processorLog;
    private Logger processorLogger;

    @BeforeEach
    void setUp() {
        processorLogger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        processorLog = new ListAppender<>();
        processorLog.start();
        processorLogger.addAppender(processorLog);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        processorLogger.detachAppender(processorLog);
        TenantContext.clear();
    }

    private void asAcme() {
        TenantContext.set(TenantIdentity.tenant(acme.slug(), acme.schemaName()));
    }

    private String row(OutboxKind kind, long reference) {
        List<String> r = jdbc.queryForList("SELECT status || '/' || attempts FROM tenant_acme.side_effect_outbox "
                + "WHERE kind = ? AND reference = ?", String.class, kind.name(), String.valueOf(reference));
        return r.isEmpty() ? "none" : r.getFirst();
    }

    private void makeDue() {
        jdbc.update("UPDATE tenant_acme.side_effect_outbox SET next_attempt_at = now() WHERE status = 'PENDING'");
    }

    private int newOrderMails(String orderNumber) {
        return (int) Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject() != null && m.subject().contains("New Order Received - " + orderNumber))
                .count();
    }

    private long orderId(String orderNumber) {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.orders WHERE order_number = ?", Long.class, orderNumber);
    }

    /** Occupies every mail thread (until {@link #release}) and every queue slot: the next hand-off is refused. */
    private void saturateMail() throws InterruptedException {
        TrackedTaskExecutor mail = (TrackedTaskExecutor) mailBean;
        CountDownLatch started = new CountDownLatch(mail.getMaxPoolSize());
        for (int i = 0; i < mail.getMaxPoolSize(); i++) {
            mail.execute(() -> {
                started.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < mail.getQueueCapacity(); i++) {
            mail.execute(() -> { });
        }
    }

    private String placeOrder(String email, String sku) throws Exception {
        Customer c = customer(acme, email);
        cart(c, product(acme, sku, "100.00", 20), 1);
        var r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    @Test
    void theRowIsPartOfThePublishingTransaction_rollbackLeavesNone_duplicateIsOneRow() {
        asAcme();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(st -> {
            store.record(OutboxKind.NEW_ORDER_ADMIN_MAIL, 900_001L);
            st.setRollbackOnly();
        });
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, 900_001L)).isEqualTo("none");
        tx.executeWithoutResult(st -> {
            store.record(OutboxKind.NEW_ORDER_ADMIN_MAIL, 900_002L);
            store.record(OutboxKind.NEW_ORDER_ADMIN_MAIL, 900_002L);
        });
        tx.executeWithoutResult(st -> store.record(OutboxKind.NEW_ORDER_ADMIN_MAIL, 900_002L)); // a replayed event
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.side_effect_outbox WHERE reference = '900002'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '60 seconds' FROM tenant_acme.side_effect_outbox "
                + "WHERE reference = '900002'", Boolean.class)).as("not due before the immediate hand-off's grace").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_bravo.side_effect_outbox", Integer.class))
                .as("the other tenant's outbox is untouched").isZero();
    }

    @Test
    void aCheckoutCommitsItsNewOrderMailRowAndTheHandOffSendsItOnce() throws Exception {
        String order = placeOrder("ob-1@acme.test", "P-OB-1");
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, orderId(order))).isEqualTo("DONE/1");
        assertThat(newOrderMails(order)).isEqualTo(1);
    }

    @Test
    void crashAfterCommit_aWorkerThatClaimedTheRowAndDied_theDrainSendsItOnceAfterTheLease() throws Exception {
        saturateMail(); // the immediate hand-off is refused: only the committed row is left, as after a crash
        String order = placeOrder("ob-2@acme.test", "P-OB-2");
        long id = orderId(order);
        release.countDown();
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/0");
        asAcme();
        // a worker claims it ... and the process dies before sending / marking it
        assertThat(store.claimFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, String.valueOf(id))).isPresent();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/1");
        assertThat(drainer.drainCurrentTenant()).as("leased: not due yet").isZero();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '5 minutes' FROM tenant_acme.side_effect_outbox "
                + "WHERE reference = ?", Boolean.class, String.valueOf(id))).isTrue();
        makeDue(); // the lease ran out
        assertThat(drainer.drainCurrentTenant()).isEqualTo(1);
        awaitBackgroundIdle();
        assertThat(newOrderMails(order)).isEqualTo(1);
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("DONE/2");
        makeDue();
        assertThat(drainer.drainCurrentTenant()).as("DONE rows are never due").isZero();
    }

    @Test
    void duplicatePrevention_theHandOffAndConcurrentDrainsRacingForOneRowSendOnce() throws Exception {
        saturateMail();
        String order = placeOrder("ob-3@acme.test", "P-OB-3");
        long id = orderId(order);
        release.countDown();
        awaitBackgroundIdle();
        assertThat(newOrderMails(order)).isZero();
        makeDue();
        int racers = 6;
        CyclicBarrier start = new CyclicBarrier(racers);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            boolean fresh = i % 2 == 0;
            Thread t = new Thread(() -> {
                TenantContext.set(TenantIdentity.tenant(acme.slug(), acme.schemaName()));
                try {
                    start.await(10, TimeUnit.SECONDS);
                    if (fresh) {
                        processor.runFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, String.valueOf(id)); // a late hand-off
                    } else {
                        drainer.drainCurrentTenant();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    TenantContext.clear();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
        awaitBackgroundIdle();
        assertThat(newOrderMails(order)).as("exactly one send").isEqualTo(1);
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("DONE/1");
    }

    @Test
    void aFailingSendIsRetriedWithBackOffThenMarkedFailedAndLoggedWithItsReference() throws Exception {
        doThrow(new IllegalStateException("smtp said no to ob-4@acme.test")).when(mailTransport)
                .send(argThat(m -> m != null && m.subject() != null && m.subject().startsWith("New Order Received")));
        String order = placeOrder("ob-4@acme.test", "P-OB-4");
        long id = orderId(order);
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/1");
        assertThat(jdbc.queryForObject("SELECT last_error FROM tenant_acme.side_effect_outbox WHERE reference = ?",
                String.class, String.valueOf(id))).isEqualTo("com.example.perfume_budget.notification.MailNotSentException");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at BETWEEN now() + interval '30 seconds' AND now() + interval '90 seconds'"
                + " FROM tenant_acme.side_effect_outbox WHERE reference = ?", Boolean.class, String.valueOf(id)))
                .as("first back-off ~1 min").isTrue();
        assertThat(processorLog.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("order " + id).contains("attempt 1 of " + OutboxStore.MAX_ATTEMPTS);
        });
        asAcme();
        assertThat(drainer.drainCurrentTenant()).as("backing off: not due").isZero();

        makeDue();
        drainer.drainCurrentTenant();
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/2");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '90 seconds' FROM tenant_acme.side_effect_outbox "
                + "WHERE reference = ?", Boolean.class, String.valueOf(id))).as("back-off doubles").isTrue();

        jdbc.update("UPDATE tenant_acme.side_effect_outbox SET attempts = ? WHERE reference = ?",
                OutboxStore.MAX_ATTEMPTS - 1, String.valueOf(id));
        makeDue();
        drainer.drainCurrentTenant();
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("FAILED/" + OutboxStore.MAX_ATTEMPTS);
        long rowId = jdbc.queryForObject("SELECT id FROM tenant_acme.side_effect_outbox WHERE reference = ?", Long.class,
                String.valueOf(id));
        assertThat(processorLog.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("NOT sent").contains("gave up").contains("order " + id)
                        .contains("outbox " + rowId).doesNotContain("ob-4@acme.test"));
        assertThat(processorLog.list).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("ob-4@acme.test");
            assertThat(e.getThrowableProxy()).isNull();
        });
        makeDue();
        assertThat(drainer.drainCurrentTenant()).as("poison rows are never retried").isZero();
    }

    /**
     * A drain reservation that outlived its lease (its task sat in the executor) is stale once anybody attempted the
     * row: it must not claim it again, which would skip the back-off and repeat the send.
     */
    @Test
    void aStaleReservationNeverClaimsARowSomebodyAttemptedSince() throws Exception {
        saturateMail();
        String order = placeOrder("ob-5@acme.test", "P-OB-5");
        long id = orderId(order);
        release.countDown();
        awaitBackgroundIdle();
        asAcme();
        makeDue();
        List<OutboxStore.Row> stale = store.reserveDue(10);
        assertThat(stale).singleElement().satisfies(r -> assertThat(r.attempts()).isZero());
        doThrow(new IllegalStateException("smtp down")).when(mailTransport)
                .send(argThat(m -> m != null && m.subject() != null && m.subject().startsWith("New Order Received")));
        processor.runFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, String.valueOf(id)); // attempt 1 fails, backs off
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/1");
        processor.runReserved(stale.getFirst());
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).as("the stale reservation claimed nothing").isEqualTo("PENDING/1");
        assertThat(Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .filter(i -> ((MailMessage) i.getArgument(0)).subject().contains("New Order Received - " + order))
                .count()).as("one attempt only").isEqualTo(1);
    }

    @Test
    void theDrainPurgesDoneRowsAfterTheRetentionAndKeepsFailedOnes() {
        jdbc.update("INSERT INTO tenant_acme.side_effect_outbox (kind, reference, status, attempts, next_attempt_at, completed_at) VALUES "
                + "('NEW_ORDER_ADMIN_MAIL', '910001', 'DONE', 1, now(), now() - interval '15 days'),"
                + "('NEW_ORDER_ADMIN_MAIL', '910002', 'DONE', 1, now(), now() - interval '13 days'),"
                + "('NEW_ORDER_ADMIN_MAIL', '910003', 'FAILED', 8, now() - interval '30 days', NULL)");
        asAcme();
        drainer.drainCurrentTenant();
        assertThat(jdbc.queryForList("SELECT reference FROM tenant_acme.side_effect_outbox WHERE reference LIKE '9100%' ORDER BY reference",
                String.class)).containsExactly("910002", "910003");
    }
}

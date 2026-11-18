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
    @Autowired private com.example.perfume_budget.config.ws.NotificationService notificationService;
    @Autowired private javax.sql.DataSource dataSource;

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

    // ---- EVT-01c ----------------------------------------------------------------------------------------------

    private boolean nextAttemptAfter(long id, String interval) {
        return jdbc.queryForObject("SELECT next_attempt_at > now() + interval '" + interval + "' FROM tenant_acme.side_effect_outbox"
                + " WHERE kind = 'NEW_ORDER_ADMIN_MAIL' AND reference = ?", Boolean.class, String.valueOf(id));
    }

    /** Makes the first send of order {@code order}'s new-order e-mail block (until {@code go}) once it entered. */
    private void blockFirstNewOrderMail(String order, CountDownLatch entered, CountDownLatch go, RuntimeException thenThrow) {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        Mockito.doAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            if (m != null && m.subject() != null && m.subject().contains("New Order Received - " + order) && first.getAndSet(false)) {
                entered.countDown();
                go.await(30, TimeUnit.SECONDS);
                if (thenThrow != null) {
                    throw thenThrow;
                }
            }
            return null;
        }).when(mailTransport).send(Mockito.any());
    }

    private Thread inAcme(Runnable r) {
        Thread t = new Thread(() -> {
            asAcme();
            try {
                r.run();
            } finally {
                TenantContext.clear();
            }
        });
        t.start();
        return t;
    }

    /** A row that is due now, never attempted (its immediate hand-off was refused). */
    private long refusedOrderRow(String email, String sku) throws Exception {
        saturateMail();
        String order = placeOrder(email, sku);
        long id = orderId(order);
        release.countDown();
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/0");
        makeDue();
        return id;
    }

    private String orderNumberOf(long orderId) {
        return jdbc.queryForObject("SELECT order_number FROM tenant_acme.orders WHERE id = ?", String.class, orderId);
    }

    /**
     * O1 probe: drain A reserved the row; a late immediate hand-off claimed it and is still sending; then drain A's
     * hand-off is refused and it releases its reservation. The release must not make the claimed row due, or drain
     * B claims it and the e-mail goes out twice.
     */
    @Test
    void o1_aReleaseOfAStaleReservationNeverMakesAClaimedRowDue_noDoubleSend() throws Exception {
        long id = refusedOrderRow("o1@acme.test", "P-O1");
        String order = orderNumberOf(id);
        asAcme();
        List<OutboxStore.Row> drainA = store.reserveDue(10);
        assertThat(drainA).singleElement().satisfies(r -> assertThat(r.attempts()).isZero());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        blockFirstNewOrderMail(order, entered, go, null);
        Thread worker = inAcme(() -> processor.runFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, String.valueOf(id)));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("the late hand-off is sending").isTrue();
            assertThat(store.release(drainA)).as("drain A's refused reservation is stale").isZero();
            assertThat(nextAttemptAfter(id, "5 minutes")).as("the sending worker's lease is intact").isTrue();
            assertThat(drainer.drainCurrentTenant()).as("drain B finds nothing due").isZero();
        } finally {
            go.countDown();
            worker.join(30_000);
        }
        awaitBackgroundIdle();
        assertThat(newOrderMails(order)).as("sent once").isEqualTo(1);
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("DONE/1");
    }

    /**
     * O2 probe: worker 1 claimed the row and hangs in the send past its lease; worker 2 re-claims it. When worker 1's
     * send finally fails, its stale fail() must not touch worker 2's claim (back-off would cut worker 2's lease short
     * and let a third worker send while worker 2 still is), and its complete() must not settle worker 2's attempt.
     */
    @Test
    void o2_aStaleWorkerNeitherFailsNorCompletesARowSomebodyReClaimed() throws Exception {
        long id = refusedOrderRow("o2@acme.test", "P-O2");
        String order = orderNumberOf(id);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        blockFirstNewOrderMail(order, entered, go, new IllegalStateException("smtp timeout"));
        Thread worker1 = inAcme(() -> processor.runFresh(OutboxKind.NEW_ORDER_ADMIN_MAIL, String.valueOf(id)));
        OutboxStore.Row worker2;
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            makeDue(); // worker 1's lease ran out
            asAcme();
            worker2 = store.claim(store.reserveDue(10).getFirst()).orElseThrow();
            assertThat(worker2.attempts()).isEqualTo(2);
        } finally {
            go.countDown();
            worker1.join(30_000);
        }
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).as("worker 1's failure was not recorded").isEqualTo("PENDING/2");
        assertThat(nextAttemptAfter(id, "5 minutes")).as("worker 2's lease is intact").isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error FROM tenant_acme.side_effect_outbox WHERE kind = 'NEW_ORDER_ADMIN_MAIL'"
                + " AND reference = ?", String.class, String.valueOf(id))).isNull();
        assertThat(processorLog.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("went stale"));

        OutboxStore.Row worker1Claim = new OutboxStore.Row(worker2.id(), worker2.kind(), worker2.reference(), 1);
        assertThat(store.complete(worker1Claim)).as("stale complete").isFalse();
        assertThat(store.fail(worker1Claim, "x", true)).isEqualTo(OutboxStore.FailOutcome.STALE);
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("PENDING/2");
        assertThat(store.complete(worker2)).isTrue();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("DONE/2");
        assertThat(store.fail(worker2, "late", true)).as("a DONE row is never put back").isEqualTo(OutboxStore.FailOutcome.STALE);
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("DONE/2");
    }

    /** R02 + R10: the reservation pushes the row out by the lease; release (real SQL) makes exactly the fenced rows due. */
    @Test
    void reserveLeasesTheRow_releaseMakesOnlyTheRowsStillAtTheirReservedAttemptsDue() {
        asAcme();
        jdbc.update("INSERT INTO tenant_acme.side_effect_outbox (kind, reference, attempts, next_attempt_at) VALUES "
                + "('NEW_ORDER_ADMIN_MAIL', '920001', 0, now() - interval '1 minute'),"
                + "('NEW_ORDER_ADMIN_MAIL', '920002', 3, now() - interval '1 minute'),"
                + "('NEW_ORDER_ADMIN_MAIL', '920003', 1, now() - interval '1 minute')");
        List<OutboxStore.Row> reserved = store.reserveDue(10);
        assertThat(reserved).extracting(OutboxStore.Row::reference).containsExactly("920001", "920002", "920003");
        assertThat(jdbc.queryForObject("SELECT bool_and(next_attempt_at BETWEEN now() + interval '9 minutes' AND now() + interval '11 minutes')"
                + " FROM tenant_acme.side_effect_outbox WHERE reference LIKE '9200%'", Boolean.class)).as("leased 10 min").isTrue();
        assertThat(store.reserveDue(10)).as("leased rows are not reserved twice").isEmpty();
        // somebody claims 920003 meanwhile (attempts 1 -> 2)
        assertThat(store.claim(reserved.get(2))).isPresent();
        assertThat(store.release(reserved)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT reference FROM tenant_acme.side_effect_outbox WHERE reference LIKE '9200%'"
                + " AND next_attempt_at <= now() ORDER BY reference", String.class)).containsExactly("920001", "920002");
        assertThat(store.release(List.of())).isZero();
    }

    /** R22 with literals: the 8th failed attempt is the last; the 7th still backs off (capped at 1 h, R05 in SQL). */
    @Test
    void theEighthFailedAttemptMarksTheRowFailed_theSeventhBacksOffOneHour() {
        asAcme();
        jdbc.update("INSERT INTO tenant_acme.side_effect_outbox (kind, reference, attempts, next_attempt_at) VALUES "
                + "('NEW_ORDER_ADMIN_MAIL', '930007', 7, now()), ('NEW_ORDER_ADMIN_MAIL', '930008', 8, now())");
        long id7 = jdbc.queryForObject("SELECT id FROM tenant_acme.side_effect_outbox WHERE reference = '930007'", Long.class);
        long id8 = jdbc.queryForObject("SELECT id FROM tenant_acme.side_effect_outbox WHERE reference = '930008'", Long.class);
        assertThat(store.fail(new OutboxStore.Row(id7, OutboxKind.NEW_ORDER_ADMIN_MAIL, "930007", 7), "e", true))
                .isEqualTo(OutboxStore.FailOutcome.RETRY);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at BETWEEN now() + interval '59 minutes' AND now() + interval '61 minutes'"
                + " FROM tenant_acme.side_effect_outbox WHERE id = ?", Boolean.class, id7)).isTrue();
        assertThat(store.fail(new OutboxStore.Row(id8, OutboxKind.NEW_ORDER_ADMIN_MAIL, "930008", 8), "e", true))
                .isEqualTo(OutboxStore.FailOutcome.FAILED);
    }

    /** EVT-01c (5): a non-retryable failure goes FAILED at its first attempt, with one ERROR naming it. */
    @Test
    void aNonRetryableFailureIsFailedAtOnce_notRetriedEightTimes() throws Exception {
        doThrow(new NullPointerException()).when(mailTransport)
                .send(argThat(m -> m != null && m.subject() != null && m.subject().startsWith("New Order Received")));
        String order = placeOrder("ob-np@acme.test", "P-OB-NP");
        long id = orderId(order);
        awaitBackgroundIdle();
        assertThat(row(OutboxKind.NEW_ORDER_ADMIN_MAIL, id)).isEqualTo("FAILED/1");
        assertThat(processorLog.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("not retryable").contains("order " + id));
        asAcme();
        makeDue();
        assertThat(drainer.drainCurrentTenant()).isZero();
    }

    /** O5: concurrent runs of the admin payment notification write one notification, race-free (V15 unique index). */
    @Test
    void o5_concurrentPaymentNotificationsForOneOrderWriteOneRow() throws Exception {
        com.example.perfume_budget.model.Order o = new com.example.perfume_budget.model.Order();
        o.setOrderNumber("EVT01C-RACE-1");
        com.example.perfume_budget.model.Payment p = new com.example.perfume_budget.model.Payment();
        p.setOrder(o);
        int racers = 8;
        CyclicBarrier start = new CyclicBarrier(racers);
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(inAcme(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    notificationService.notifyAdminsOfSuccessfulPayment(p);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
        assertThat(errors).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notifications WHERE type = 'PAYMENT_SUCCESS'"
                + " AND reference_id = 'EVT01C-RACE-1'", Integer.class)).isEqualTo(1);
        int admins = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.users WHERE roles IN ('ADMIN', 'FRONT_DESK')", Integer.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notification_recipients r JOIN tenant_acme.notifications n"
                + " ON n.id = r.notification_id WHERE n.reference_id = 'EVT01C-RACE-1'", Integer.class)).isEqualTo(admins);
        jdbc.update("DELETE FROM tenant_acme.notification_recipients WHERE notification_id IN"
                + " (SELECT id FROM tenant_acme.notifications WHERE reference_id = 'EVT01C-RACE-1')");
        jdbc.update("DELETE FROM tenant_acme.notifications WHERE reference_id = 'EVT01C-RACE-1'");
    }

    /** V15 on a schema that already has duplicate payment notifications: merged into the oldest, read state kept. */
    @Test
    void v15MergesExistingDuplicatePaymentNotificationsSafely() {
        String schema = "tenant_evt01c_v15";
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migrations/tenant").target("13").load().migrate();
            String user = "INSERT INTO " + schema + ".users (full_name, email, password, roles, created_at) VALUES (?, ?, 'x', 'ADMIN', now())";
            jdbc.update(user, "A", "a@v15.test");
            jdbc.update(user, "B", "b@v15.test");
            jdbc.update(user, "C", "c@v15.test");
            String n = "INSERT INTO " + schema + ".notifications (type, title, message, reference_type, reference_id, created_at)"
                    + " VALUES (?, 't', 'm', 'ORDER', ?, now())";
            jdbc.update(n, "PAYMENT_SUCCESS", "PB-1");      // 1 kept
            jdbc.update(n, "PAYMENT_SUCCESS", "PB-1");      // 2 duplicate
            jdbc.update(n, "PAYMENT_SUCCESS", "PB-1");      // 3 duplicate
            jdbc.update(n, "PAYMENT_SUCCESS", "PB-2");      // 4 single
            jdbc.update(n, "SHOP_FLOOR_RESTOCK", "77");     // 5 other type, repeats allowed
            jdbc.update(n, "SHOP_FLOOR_RESTOCK", "77");     // 6
            String r = "INSERT INTO " + schema + ".notification_recipients (notification_id, user_id, is_read, read_at, created_at)"
                    + " VALUES (?, ?, ?, ?, now())";
            jdbc.update(r, 1, 1, false, null);                       // A on kept, unread
            jdbc.update(r, 2, 1, true, java.sql.Timestamp.valueOf("2026-11-01 10:00:00")); // A read the duplicate
            jdbc.update(r, 2, 2, false, null);                       // B only on duplicates (read on one)
            jdbc.update(r, 3, 2, true, java.sql.Timestamp.valueOf("2026-11-02 10:00:00"));
            jdbc.update(r, 3, 3, false, null);                       // C only on a duplicate
            jdbc.update(r, 5, 1, false, null);
            jdbc.update(r, 6, 1, false, null);

            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migrations/tenant").load().migrate();

            assertThat(jdbc.queryForList("SELECT id FROM " + schema + ".notifications ORDER BY id", Long.class))
                    .containsExactly(1L, 4L, 5L, 6L);
            List<String> recipients = jdbc.queryForList("SELECT notification_id || ':' || user_id || ':' || is_read || ':' || coalesce(read_at::text, '-')"
                    + " FROM " + schema + ".notification_recipients ORDER BY notification_id, user_id", String.class);
            assertThat(recipients).containsExactly(
                    "1:1:true:2026-11-01 10:00:00", "1:2:true:2026-11-02 10:00:00", "1:3:false:-", "5:1:false:-", "6:1:false:-");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = "
                    + "'uk_notifications_payment_success_reference'", Integer.class, schema)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(n, "PAYMENT_SUCCESS", "PB-2"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update(n, "SHOP_FLOOR_RESTOCK", "77"); // still allowed
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}

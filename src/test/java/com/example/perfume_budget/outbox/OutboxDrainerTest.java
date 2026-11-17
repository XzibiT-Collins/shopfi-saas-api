package com.example.perfume_budget.outbox;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.tenancy.TenantRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EVT-01c (7): per-row hand-off, release only what was refused (fenced rows), hourly purge, own scheduler thread. */
class OutboxDrainerTest {

    private final OutboxStore store = mock(OutboxStore.class);
    private final OutboxProcessor processor = mock(OutboxProcessor.class);
    private final TenantRunner runner = mock(TenantRunner.class);
    private final List<Runnable> mailRan = new ArrayList<>();
    private final List<Runnable> notifyRan = new ArrayList<>();
    private OutboxDrainScheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    private static OutboxStore.Row row(long id, OutboxKind kind, int attempts) {
        return new OutboxStore.Row(id, kind, String.valueOf(id), attempts);
    }

    private OutboxDrainer drainer(Executor mail, Executor notify) {
        return new OutboxDrainer(store, processor, new MailDispatcher(mail, notify), runner);
    }

    @SuppressWarnings("unchecked")
    private List<OutboxStore.Row> released() {
        ArgumentCaptor<Collection<OutboxStore.Row>> c = ArgumentCaptor.forClass(Collection.class);
        verify(store).release(c.capture());
        return new ArrayList<>(c.getValue());
    }

    @Test
    void aFullMailExecutorReleasesOnlyItsOwnRows_notificationRowsAreStillHandedOff() {
        OutboxStore.Row m1 = row(1, OutboxKind.PAYMENT_RECEIPT, 0);
        OutboxStore.Row n1 = row(2, OutboxKind.PAYMENT_ADMIN_NOTIFICATION, 3);
        OutboxStore.Row m2 = row(3, OutboxKind.NEW_ORDER_ADMIN_MAIL, 1);
        OutboxStore.Row n2 = row(4, OutboxKind.PAYMENT_ADMIN_NOTIFICATION, 0);
        when(store.reserveDue(anyInt())).thenReturn(List.of(m1, n1, m2, n2));
        AtomicInteger mailOffers = new AtomicInteger();
        Executor fullMail = r -> {
            mailOffers.incrementAndGet();
            throw new RejectedExecutionException("queue full");
        };
        int handedOff = drainer(fullMail, notifyRan::add).drainCurrentTenant();
        assertThat(handedOff).isEqualTo(2);
        assertThat(notifyRan).hasSize(2);
        assertThat(mailOffers).as("no hand-off storm: one refusal per executor and drain").hasValue(1);
        assertThat(released()).containsExactly(m1, m2);
    }

    @Test
    void aRowRefusedInTheMiddleDoesNotReleaseTheRowsHandedOffBeforeIt() {
        OutboxStore.Row a = row(1, OutboxKind.PAYMENT_RECEIPT, 0);
        OutboxStore.Row b = row(2, OutboxKind.PAYMENT_ADMIN_MAIL, 0);
        OutboxStore.Row c = row(3, OutboxKind.NEW_ORDER_ADMIN_MAIL, 2);
        when(store.reserveDue(anyInt())).thenReturn(List.of(a, b, c));
        AtomicInteger slots = new AtomicInteger(1);
        Executor oneSlot = r -> {
            if (slots.getAndDecrement() <= 0) throw new RejectedExecutionException("queue full");
            mailRan.add(r);
        };
        assertThat(drainer(oneSlot, notifyRan::add).drainCurrentTenant()).isEqualTo(1);
        assertThat(released()).as("with the attempts they were reserved at").containsExactly(b, c);
    }

    @Test
    void nothingRefused_nothingReleased() {
        when(store.reserveDue(anyInt())).thenReturn(List.of(row(1, OutboxKind.PAYMENT_RECEIPT, 0)));
        assertThat(drainer(mailRan::add, notifyRan::add).drainCurrentTenant()).isEqualTo(1);
        verify(store, never()).release(any());
    }

    @Test
    void theRoundPurgesAtMostOncePerHour_eachDrainIsOneReservation() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            ((Runnable) inv.getArgument(0)).run(); // two tenants
            return null;
        }).when(runner).forEachActiveTenant(any());
        when(store.reserveDue(anyInt())).thenReturn(List.of());
        OutboxDrainer d = drainer(mailRan::add, notifyRan::add);
        Instant t0 = Instant.parse("2026-11-17T10:00:00Z");
        d.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        d.drainAllTenants();
        verify(store, times(2)).purgeDone(OutboxDrainer.RETENTION);
        d.setClock(Clock.fixed(t0.plus(Duration.ofMinutes(59)), ZoneOffset.UTC));
        d.drainAllTenants();
        verify(store, times(2)).purgeDone(any());
        d.setClock(Clock.fixed(t0.plus(Duration.ofMinutes(60)), ZoneOffset.UTC));
        d.drainAllTenants();
        verify(store, times(4)).purgeDone(any());
        verify(store, times(6)).reserveDue(OutboxDrainer.BATCH);
        assertThat(OutboxDrainer.PURGE_EVERY).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxDrainer.RETENTION).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void theSchedulerDrainsOnItsOwnThread_andKeepsGoingAfterAFailedRound() throws Exception {
        OutboxDrainer d = mock(OutboxDrainer.class);
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch rounds = new CountDownLatch(3);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            rounds.countDown();
            if (first.getAndSet(false)) {
                throw new IllegalStateException("database down");
            }
            return null;
        }).when(d).drainAllTenants();
        scheduler = new OutboxDrainScheduler(d, 10, 0);
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(rounds.await(10, TimeUnit.SECONDS)).as("rounds continue after a failure").isTrue();
        assertThat(threads).allSatisfy(t -> assertThat(t).startsWith(OutboxDrainScheduler.THREAD_PREFIX));
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void theSchedulerHonoursTheConfiguredInitialDelay() throws Exception {
        OutboxDrainer d = mock(OutboxDrainer.class);
        scheduler = new OutboxDrainScheduler(d, 10, 60_000);
        scheduler.start();
        Thread.sleep(200);
        verify(d, never()).drainAllTenants();
    }
}

package com.example.perfume_budget.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** EVT-01d (2): every lock wait of a webhook settlement is derived from the settlement's remaining deadline. */
class SettlementDeadlineTest {

    @AfterEach
    void noTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static long in(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void withoutABoundDeadline_theCapIsUsedAsIs_andNoSqlRuns() {
        assertThat(SettlementDeadline.active()).isFalse();
        assertThat(SettlementDeadline.limit(5000)).isEqualTo(5000);
        assertThat(SettlementDeadline.remainingMillis()).isEqualTo(Long.MAX_VALUE);
        EntityManager em = mock(EntityManager.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        SettlementDeadline.beforeLockWait(em);
        verifyNoInteractions(em);
        assertThat(SettlementDeadline.withCap(10, () -> "ran")).isEqualTo("ran");
    }

    @Test
    void theLimitIsTheSmallestOfTheCallersCap_theBoundCap_andTheTimeLeft() {
        SettlementDeadline.run(in(20_000), 8000, () -> {
            assertThat(SettlementDeadline.active()).isTrue();
            assertThat(SettlementDeadline.limit(5000)).isEqualTo(5000);   // coupon cap below the rest
            assertThat(SettlementDeadline.limit(60_000)).isEqualTo(8000); // bound per-wait cap
            return null;
        });
        SettlementDeadline.run(in(1200), 8000, () -> {
            long l = SettlementDeadline.limit(5000);
            assertThat(l).isBetween(1000L, 1200L); // the time left, not the fixed 5 s
            return null;
        });
        assertThat(SettlementDeadline.active()).as("unbound after run").isFalse();
    }

    @Test
    void aPassedDeadlineFailsTheLockPoint_insteadOfWaiting() {
        SettlementDeadline.run(in(-1), 8000, () -> {
            assertThatThrownBy(() -> SettlementDeadline.limit(5000))
                    .isInstanceOf(WebhookProcessorImpl.WebhookDeadlineExceededException.class);
            EntityManager em = mock(EntityManager.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> SettlementDeadline.beforeLockWait(em))
                    .isInstanceOf(WebhookProcessorImpl.WebhookDeadlineExceededException.class);
            verifyNoInteractions(em);
            return null;
        });
    }

    @Test
    void beforeLockWait_setsBothLimitsForThisTransactionOnly_fromTheTimeLeft() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(new Object[]{"x", "y"});
        TransactionSynchronizationManager.setActualTransactionActive(true);
        SettlementDeadline.run(in(3000), 8000, () -> {
            SettlementDeadline.beforeLockWait(em);
            return null;
        });
        verify(em).createNativeQuery(
                "SELECT set_config('lock_timeout', :l, true), set_config('statement_timeout', :s, true)");
        org.mockito.ArgumentCaptor<Object> l = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.ArgumentCaptor<Object> s = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(q).setParameter(eq("l"), l.capture());
        verify(q).setParameter(eq("s"), s.capture());
        long lock = Long.parseLong(l.getValue().toString().replace("ms", ""));
        long stmt = Long.parseLong(s.getValue().toString().replace("ms", ""));
        assertThat(lock).isBetween(2500L, 3000L); // min(8 s cap, ~3 s left)
        assertThat(stmt).isBetween(2500L, 3000L);
    }

    @Test
    void beforeLockWait_outsideATransaction_runsNoSql() {
        EntityManager em = mock(EntityManager.class);
        SettlementDeadline.run(in(3000), 8000, () -> {
            SettlementDeadline.beforeLockWait(em);
            return null;
        });
        verifyNoInteractions(em);
    }

    @Test
    void withCap_narrowsTheCapOnlyWhileItRuns_andNeverWidensIt() {
        SettlementDeadline.run(in(20_000), 8000, () -> {
            SettlementDeadline.withCap(5000, () -> {
                assertThat(SettlementDeadline.limit(Long.MAX_VALUE)).isEqualTo(5000);
                return null;
            });
            assertThat(SettlementDeadline.limit(Long.MAX_VALUE)).isEqualTo(8000);
            SettlementDeadline.withCap(60_000, () -> {
                assertThat(SettlementDeadline.limit(Long.MAX_VALUE)).isEqualTo(8000);
                return null;
            });
            // restored even when the section throws
            assertThatThrownBy(() -> SettlementDeadline.withCap(10, () -> {
                throw new IllegalStateException("x");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(SettlementDeadline.limit(Long.MAX_VALUE)).isEqualTo(8000);
            return null;
        });
    }

    @Test
    void theBindingIsRemovedEvenWhenTheSettlementThrows_andIsPerThread() throws Exception {
        assertThatThrownBy(() -> SettlementDeadline.run(in(20_000), 8000, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(SettlementDeadline.active()).isFalse();
        java.util.concurrent.atomic.AtomicReference<Boolean> seenElsewhere = new java.util.concurrent.atomic.AtomicReference<>();
        SettlementDeadline.run(in(20_000), 8000, () -> {
            Thread other = new Thread(() -> seenElsewhere.set(SettlementDeadline.active()));
            other.start();
            try {
                other.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertThat(seenElsewhere.get()).as("another thread sees no deadline").isFalse();
    }
}

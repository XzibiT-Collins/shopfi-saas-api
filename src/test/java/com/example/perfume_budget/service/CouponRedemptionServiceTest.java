package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** API-04 rework: the coupon usage counter (reserve / release / per-order release and re-acquire). */
class CouponRedemptionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);

    private final CouponRepository repo = mock(CouponRepository.class);
    private final DiscountCalculationUtil util = new DiscountCalculationUtil(repo, CLOCK);
    private final CouponRedemptionService service = new CouponRedemptionService(repo, util, new PassThroughLockTimeout(),
            CLOCK, mock(PlatformTransactionManager.class));

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(CouponRedemptionService.PENDING_RESOURCE);
    }

    /** Simulates the commit of the surrounding transaction: beforeCommit then afterCompletion of every sync. */
    private static void commit() {
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(s -> s.beforeCommit(false));
        syncs.forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static Coupon coupon(boolean active, int used, int limit) {
        return Coupon.builder().id(7L).code("SAVE").isActive(active).usageCount(used).usageLimit(limit)
                .startDate(LocalDate.of(2026, 1, 1)).expirationDate(LocalDate.of(2026, 12, 31)).build();
    }

    @Test
    void reserveNormalisesTheCodeWithLocaleRoot_evenUnderATurkishDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            when(repo.findIdByCode("DISCOUNT10")).thenReturn(Optional.of(7L));
            when(repo.reserveRedemption(7L, LocalDate.of(2026, 6, 1))).thenReturn(1);

            CouponRedemptionService.Reservation r = service.reserve("  discount10 ");

            assertThat(r.reserved()).isTrue();
            assertThat(r.couponId()).isEqualTo(7L);
            assertThat(CouponRedemptionService.normaliseCode("discount10")).isEqualTo("DISCOUNT10");
            assertThat(com.example.perfume_budget.utils.CheckoutFingerprint.normalizeCoupon("discount10")).isEqualTo("DISCOUNT10");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void reserveUsesTheClockDateForTheWindow() {
        when(repo.findIdByCode("SAVE")).thenReturn(Optional.of(7L));
        when(repo.reserveRedemption(anyLong(), any())).thenReturn(1);
        service.reserve("save");
        verify(repo).reserveRedemption(7L, LocalDate.of(2026, 6, 1));
    }

    @Test
    void unknownCodeIsANotFoundRejection_noUpdate() {
        when(repo.findIdByCode("NOPE")).thenReturn(Optional.empty());
        CouponRedemptionService.Reservation r = service.reserve("nope");
        assertThat(r.reserved()).isFalse();
        assertThat(r.rejection().getReason()).isEqualTo(Reason.NOT_FOUND);
        verify(repo, never()).reserveRedemption(anyLong(), any());
    }

    @Test
    void refusedReservationReportsTheSpecificReason() {
        when(repo.findIdByCode("SAVE")).thenReturn(Optional.of(7L));
        when(repo.reserveRedemption(anyLong(), any())).thenReturn(0);

        when(repo.findById(7L)).thenReturn(Optional.of(coupon(false, 0, 5)));
        assertThat(service.reserve("SAVE").rejection().getReason()).isEqualTo(Reason.INACTIVE);

        when(repo.findById(7L)).thenReturn(Optional.of(coupon(true, 5, 5)));
        assertThat(service.reserve("SAVE").rejection().getReason()).isEqualTo(Reason.USAGE_LIMIT);

        // looks redeemable when re-read: someone took the last redemption in between
        when(repo.findById(7L)).thenReturn(Optional.of(coupon(true, 4, 5)));
        CouponRejectedException e = service.reserve("SAVE").rejection();
        assertThat(e.getReason()).isEqualTo(Reason.USAGE_LIMIT);

        when(repo.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.reserve("SAVE").rejection().getReason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void reserveRefusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.reserve("SAVE")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void releaseReservationGivesBackOnlyARealReservation() {
        service.releaseReservation(null);
        service.releaseReservation(new CouponRedemptionService.Reservation(7L,
                new CouponRejectedException(Reason.USAGE_LIMIT, "x")));
        verify(repo, never()).releaseRedemption(anyLong());

        service.releaseReservation(new CouponRedemptionService.Reservation(7L, null));
        verify(repo, times(1)).releaseRedemption(7L);
    }

    @Test
    void releaseReservationRetriesThenGivesUpWithoutThrowing() {
        when(repo.releaseRedemption(7L)).thenThrow(new CannotAcquireLockException("busy"));
        service.releaseReservation(new CouponRedemptionService.Reservation(7L, null));
        verify(repo, times(CouponRedemptionService.RELEASE_ATTEMPTS)).releaseRedemption(7L);
    }

    @Test
    void releaseForOrderIsExactlyOnce_andNoOpWithoutACoupon() {
        TransactionSynchronizationManager.initSynchronization();
        Order noCoupon = Order.builder().orderNumber("O-1").build();
        service.releaseForOrder(noCoupon);
        assertThat(noCoupon.isCouponRedemptionReleased()).isFalse();
        verify(repo, never()).releaseRedemption(anyLong());

        Order order = Order.builder().orderNumber("O-2").coupon(coupon(true, 1, 5)).build();
        service.releaseForOrder(order);
        service.releaseForOrder(order);
        assertThat(order.isCouponRedemptionReleased()).isTrue();
        verify(repo, never()).releaseRedemption(anyLong()); // deferred to the commit: coupon row LAST
        commit();
        verify(repo, times(1)).releaseRedemption(7L);
    }

    @Test
    void counterUpdatesRunAtCommit_afterAFlushOfEverythingElse_inCouponIdOrder() {
        TransactionSynchronizationManager.initSynchronization();
        Coupon nine = coupon(true, 1, 5);
        nine.setId(9L);
        Order a = Order.builder().orderNumber("O-A").coupon(nine).build();
        Order b = Order.builder().orderNumber("O-B").coupon(coupon(true, 1, 5)).couponRedemptionReleased(true).build();
        service.releaseForOrder(a);       // coupon 9
        service.reacquireForOrder(b);     // coupon 7
        verifyNoInteractions(repo);

        commit();

        var inOrder = inOrder(repo);
        inOrder.verify(repo).flush();
        inOrder.verify(repo).reacquireRedemption(7L);
        inOrder.verify(repo).releaseRedemption(9L);
        inOrder.verifyNoMoreInteractions();
        assertThat(TransactionSynchronizationManager.getResource(CouponRedemptionService.PENDING_RESOURCE))
                .as("unbound after completion").isNull();
    }

    @Test
    void aLockTimeoutOnTheDeferredUpdateFailsTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        when(repo.releaseRedemption(7L)).thenThrow(new com.example.perfume_budget.exception.ApiCodeException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, CouponRedemptionService.BUSY, "busy"));
        service.releaseForOrder(Order.builder().orderNumber("O-5").coupon(coupon(true, 1, 5)).build());
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThatThrownBy(() -> syncs.forEach(s -> s.beforeCommit(false)))
                .isInstanceOf(com.example.perfume_budget.exception.ApiCodeException.class);
    }

    @Test
    void releaseOutsideATransactionIsRefused() {
        Order order = Order.builder().orderNumber("O-6").coupon(coupon(true, 1, 5)).build();
        assertThatThrownBy(() -> service.releaseForOrder(order)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reacquireOnlyForAReleasedOrder() {
        TransactionSynchronizationManager.initSynchronization();
        Order live = Order.builder().orderNumber("O-3").coupon(coupon(true, 1, 5)).build();
        service.reacquireForOrder(live);
        verify(repo, never()).reacquireRedemption(anyLong());

        live.setCouponRedemptionReleased(true);
        service.reacquireForOrder(live);
        service.reacquireForOrder(live);
        assertThat(live.isCouponRedemptionReleased()).isFalse();

        Order noCoupon = Order.builder().orderNumber("O-4").couponRedemptionReleased(true).build();
        service.reacquireForOrder(noCoupon);
        commit();
        verify(repo, times(1)).reacquireRedemption(7L);
        verify(repo, times(1)).reacquireRedemption(anyLong());
    }

    @Test
    void lockTimeoutDetectionIsByTheSqlState() {
        assertThat(DbLockTimeout.isLockTimeout(new CannotAcquireLockException("x",
                new java.sql.SQLException("lock timeout", "55P03")))).isTrue();
        assertThat(DbLockTimeout.isLockTimeout(new CannotAcquireLockException("x",
                new java.sql.SQLException("deadlock", "40P01")))).isFalse();
        assertThat(DbLockTimeout.isLockTimeout(new jakarta.persistence.LockTimeoutException("x"))).isTrue();
        assertThat(DbLockTimeout.isLockTimeout(new RuntimeException("x"))).isFalse();
    }

    @Test
    void transientFailuresAreDeadlockSerializationLockTimeoutAndBusy_notOptimisticLocking() {
        assertThat(DbLockTimeout.isTransient(new CannotAcquireLockException("x",
                new java.sql.SQLException("deadlock", "40P01")))).isTrue();
        assertThat(DbLockTimeout.isTransient(new RuntimeException("commit",
                new java.sql.SQLException("deadlock", "40P01")))).as("wrapped anyhow").isTrue();
        assertThat(DbLockTimeout.isTransient(new RuntimeException(new java.sql.SQLException("ser", "40001")))).isTrue();
        assertThat(DbLockTimeout.isTransient(new RuntimeException(new java.sql.SQLException("lt", "55P03")))).isTrue();
        assertThat(DbLockTimeout.isTransient(new org.springframework.dao.DeadlockLoserDataAccessException("x", null))).isTrue();
        assertThat(DbLockTimeout.isTransient(new com.example.perfume_budget.exception.ApiCodeException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, CouponRedemptionService.BUSY, "b"))).isTrue();
        assertThat(DbLockTimeout.isTransient(new com.example.perfume_budget.exception.ApiCodeException(
                org.springframework.http.HttpStatus.CONFLICT, "X", "b"))).isFalse();
        assertThat(DbLockTimeout.isTransient(new org.springframework.orm.ObjectOptimisticLockingFailureException("P", 1L)))
                .as("API-05 owns optimistic locking").isFalse();
        assertThat(DbLockTimeout.isTransient(new RuntimeException(new java.sql.SQLException("dup", "23505")))).isFalse();
        assertThat(DbLockTimeout.isTransient(new IllegalStateException("x"))).isFalse();
    }
}

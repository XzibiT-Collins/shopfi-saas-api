package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The coupon usage counter (API-04 rework). {@code coupons.usage_count} counts LIVE redemptions: checkouts in flight
 * plus orders that are not CANCELLED / FAILED / expired.
 *
 * <p><b>No coupon lock across HTTP.</b> Checkout reserves a redemption with one atomic conditional UPDATE in its OWN
 * short transaction ({@link #reserve}) that commits before the checkout transaction starts, so the coupon row lock is
 * held for one statement, never across the Paystack call. If the checkout does not create an order with it (replay,
 * rejection, Paystack failure, any error) the caller gives it back with {@link #releaseReservation}.
 *
 * <p><b>Orders.</b> An order that goes to CANCELLED / FAILED / expired gives its redemption back exactly once
 * ({@link #releaseForOrder}, under the order row lock, idempotent through {@code orders.coupon_redemption_released});
 * a late payment that revives such an order takes it again ({@link #reacquireForOrder}).
 *
 * <p><b>Lock order (API-04 rework 2): order -> stock -> coupon, coupon LAST.</b> The coupon counter UPDATE of
 * {@link #releaseForOrder} / {@link #reacquireForOrder} is not run where it is called: it is deferred to the
 * transaction's {@code beforeCommit}, after a flush of everything else the transaction wrote (order, payment, stock
 * layers, products, ledger). So the coupon row is locked by the very last statement before COMMIT, for the time of
 * one UPDATE, and never while the transaction waits for a stock / product row that a checkout holds across its
 * Paystack call. Every transaction that changes the counter follows the same order, so they cannot deadlock with
 * each other on coupon vs stock. Checkout never takes an order row lock and changes the counter only in its own
 * single-statement transactions ({@link #reserve}, {@link #releaseReservation}) holding no other lock. Several coupons
 * in one transaction are updated in coupon id order.
 *
 * <p>Every coupon row-lock wait is bounded by {@link DbLockTimeout} (503 {@code COUPON_BUSY}).
 */
@Slf4j
@Service
public class CouponRedemptionService {

    public static final String BUSY = "COUPON_BUSY";
    public static final String BUSY_MESSAGE = "This coupon is being updated right now. Please try again.";
    static final int RELEASE_ATTEMPTS = 3;

    private final CouponRepository couponRepository;
    private final DiscountCalculationUtil discountCalculationUtil;
    private final DbLockTimeout lockTimeout;
    private final Clock systemClock;
    private final TransactionTemplate tx;

    public CouponRedemptionService(CouponRepository couponRepository, DiscountCalculationUtil discountCalculationUtil,
                                   DbLockTimeout lockTimeout, Clock systemClock, PlatformTransactionManager txManager) {
        this.couponRepository = couponRepository;
        this.discountCalculationUtil = discountCalculationUtil;
        this.lockTimeout = lockTimeout;
        this.systemClock = systemClock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Outcome of {@link #reserve}: either a reserved redemption of {@code couponId}, or the rejection to report when
     * (and only when) the checkout really needs the coupon (an idempotent replay never does).
     */
    public record Reservation(Long couponId, CouponRejectedException rejection) {
        public boolean reserved() {
            return rejection == null;
        }
    }

    public static String normaliseCode(String raw) {
        return raw.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Reserves one redemption in its own transaction, which has committed (lock released) when this returns. Must
     * not be called inside a transaction: the UPDATE's row lock would then be held until the caller commits.
     */
    public Reservation reserve(String rawCode) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Coupon reservation must run outside a transaction");
        }
        String code = normaliseCode(rawCode);
        return tx.execute(status -> {
            Long id = couponRepository.findIdByCode(code).orElse(null);
            if (id == null) {
                return new Reservation(null, new CouponRejectedException(Reason.NOT_FOUND,
                        CheckoutPricingService.INVALID_COUPON_CODE));
            }
            int updated = lockTimeout.run(BUSY, BUSY_MESSAGE,
                    () -> couponRepository.reserveRedemption(id, LocalDate.now(systemClock)));
            if (updated == 1) {
                return new Reservation(id, null);
            }
            return new Reservation(id, whyNotRedeemable(id));
        });
    }

    /** The specific reason a reservation was refused (same messages and reasons as the pricing checks). */
    private CouponRejectedException whyNotRedeemable(Long id) {
        Coupon coupon = couponRepository.findById(id).orElse(null);
        if (coupon == null) {
            return new CouponRejectedException(Reason.NOT_FOUND, CheckoutPricingService.INVALID_COUPON_CODE);
        }
        try {
            discountCalculationUtil.checkIfCouponIsValid(coupon);
        } catch (CouponRejectedException e) {
            return e;
        }
        // valid when re-read: another checkout took the last redemption between the two statements
        return new CouponRejectedException(Reason.USAGE_LIMIT, "Coupon usage limit has been reached");
    }

    /**
     * Gives back a reservation the checkout did not turn into an order, in its own transaction. Retried a few times on
     * a lock timeout; if it still cannot run the redemption stays counted (logged, the admin can raise the limit).
     */
    public void releaseReservation(Reservation reservation) {
        if (reservation == null || !reservation.reserved()) {
            return;
        }
        for (int attempt = 1; attempt <= RELEASE_ATTEMPTS; attempt++) {
            try {
                tx.executeWithoutResult(status -> lockTimeout.run(BUSY, BUSY_MESSAGE,
                        () -> couponRepository.releaseRedemption(reservation.couponId())));
                return;
            } catch (RuntimeException e) {
                if (attempt == RELEASE_ATTEMPTS) {
                    log.error("Could not release a coupon reservation (coupon id {}): {}", reservation.couponId(),
                            e.getClass().getSimpleName());
                    return;
                }
            }
        }
    }

    /**
     * The order went to CANCELLED / FAILED / expired: give its redemption back once. The caller holds the order row
     * lock. No-op for an order without a coupon or already released. The flag is set now; the counter UPDATE runs at
     * the end of the transaction (see the class comment: coupon row LAST).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForOrder(Order order) {
        if (order.getCoupon() == null || order.isCouponRedemptionReleased()) {
            return;
        }
        order.setCouponRedemptionReleased(true);
        defer(order.getCoupon().getId(), CounterChange.RELEASE);
        log.info("Coupon redemption of order {} released", order.getOrderNumber());
    }

    /**
     * A late payment revived a cancelled / failed order: it holds a redemption again (caller holds the order lock).
     * The counter UPDATE runs at the end of the transaction, after the stock / sold-count / ledger writes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reacquireForOrder(Order order) {
        if (order.getCoupon() == null || !order.isCouponRedemptionReleased()) {
            return;
        }
        order.setCouponRedemptionReleased(false);
        defer(order.getCoupon().getId(), CounterChange.REACQUIRE);
        log.info("Coupon redemption of revived order {} taken again", order.getOrderNumber());
    }

    enum CounterChange { RELEASE, REACQUIRE }

    static final String PENDING_RESOURCE = CouponRedemptionService.class.getName() + ".pendingCounterChanges";

    private void defer(Long couponId, CounterChange change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Coupon counter changes must run inside a transaction");
        }
        PendingCounterChanges pending = (PendingCounterChanges) TransactionSynchronizationManager.getResource(PENDING_RESOURCE);
        if (pending == null) {
            pending = new PendingCounterChanges();
            TransactionSynchronizationManager.bindResource(PENDING_RESOURCE, pending);
            TransactionSynchronizationManager.registerSynchronization(pending);
        }
        pending.add(couponId, change);
    }

    /** Applies the deferred counter changes of one transaction just before it commits (coupon row LAST). */
    final class PendingCounterChanges implements TransactionSynchronization {
        private final Map<Long, List<CounterChange>> changes = new TreeMap<>();

        void add(Long couponId, CounterChange change) {
            changes.computeIfAbsent(couponId, id -> new ArrayList<>()).add(change);
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            // 1. everything else this transaction wrote (order, payment, stock layers, products, ledger) goes to the
            //    database first, with the caller's own lock semantics (no coupon lock is held while it may wait)
            couponRepository.flush();
            // 2. then the coupon rows, in id order, each wait bounded by lock_timeout (503 COUPON_BUSY)
            changes.forEach((couponId, list) -> list.forEach(change -> lockTimeout.run(BUSY, BUSY_MESSAGE,
                    () -> change == CounterChange.RELEASE
                            ? couponRepository.releaseRedemption(couponId)
                            : couponRepository.reacquireRedemption(couponId))));
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_RESOURCE);
        }
    }
}

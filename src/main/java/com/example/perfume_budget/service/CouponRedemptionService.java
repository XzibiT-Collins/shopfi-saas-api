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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

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
 * a late payment that revives such an order takes it again ({@link #reacquireForOrder}). Lock order is always
 * order -> coupon; checkout never takes an order row lock, so there is no cycle.
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
     * lock and saves the order afterwards. No-op for an order without a coupon or already released.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForOrder(Order order) {
        if (order.getCoupon() == null || order.isCouponRedemptionReleased()) {
            return;
        }
        order.setCouponRedemptionReleased(true);
        Long couponId = order.getCoupon().getId();
        lockTimeout.run(BUSY, BUSY_MESSAGE, () -> couponRepository.releaseRedemption(couponId));
        log.info("Coupon redemption of order {} released", order.getOrderNumber());
    }

    /** A late payment revived a cancelled / failed order: it holds a redemption again (caller holds the order lock). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reacquireForOrder(Order order) {
        if (order.getCoupon() == null || !order.isCouponRedemptionReleased()) {
            return;
        }
        order.setCouponRedemptionReleased(false);
        Long couponId = order.getCoupon().getId();
        lockTimeout.run(BUSY, BUSY_MESSAGE, () -> couponRepository.reacquireRedemption(couponId));
        log.info("Coupon redemption of revived order {} taken again", order.getOrderNumber());
    }
}

package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The per-customer coupon limit, shared by checkout, the cart preview and {@code GET /tax?couponCode=} so they always
 * agree. Counts the customer's orders that still hold a live redemption (not cancelled / failed / expired).
 */
@Component
@RequiredArgsConstructor
public class CouponPerUserLimit {

    public static final String MESSAGE = "You have already used this coupon the maximum number of times";
    /**
     * API-06: the one machine-readable code for this refusal, on the checkout 400 ({@code data.code}), on
     * {@code GET /tax?couponCode=} and as the cart preview's {@code coupon.reason}. Only reached for a coupon that is
     * otherwise valid, so it reveals nothing to a customer who has not already redeemed the coupon.
     */
    public static final String CODE = "COUPON_PER_USER_LIMIT";

    private final OrderRepository orderRepository;

    /** No-op without a limit or without a known customer. */
    public void check(Coupon coupon, Long userId) {
        Integer perUser = coupon.getPerUserUsageLimit();
        if (perUser != null && userId != null
                && orderRepository.countCouponRedemptionsByUser(userId, coupon.getId()) >= perUser) {
            throw new CouponRejectedException(Reason.PER_USER_LIMIT, MESSAGE);
        }
    }
}

package com.example.perfume_budget.utils;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class DiscountCalculationUtil {
    private final CouponRepository couponRepository;
    private final Clock systemClock;

    public void checkIfCouponIsValid(Coupon coupon) {
        LocalDate now = LocalDate.now(systemClock);

        if (!Boolean.TRUE.equals(coupon.getIsActive())) {
            throw new CouponRejectedException(Reason.INACTIVE, "Coupon is inactive");
        }

        if (now.isBefore(coupon.getStartDate())) {
            throw new CouponRejectedException(Reason.NOT_STARTED, "Coupon is not yet active");
        }

        if (now.isAfter(coupon.getExpirationDate())) {
            throw new CouponRejectedException(Reason.EXPIRED, "Coupon has expired");
        }

        if (coupon.getUsageLimit() != null
                && (coupon.getUsageCount() == null ? 0 : coupon.getUsageCount()) >= coupon.getUsageLimit()) {
            throw new CouponRejectedException(Reason.USAGE_LIMIT, "Coupon usage limit has been reached");
        }
    }

    public BigDecimal calculateDiscount(Coupon coupon, BigDecimal orderSubtotal) {
        if (coupon.getMinimumCartAmountForDiscount() != null &&
                orderSubtotal.compareTo(coupon.getMinimumCartAmountForDiscount()) < 0) {
            throw new CouponRejectedException(Reason.MIN_AMOUNT, "Order total must be at least " +
                    coupon.getMinimumCartAmountForDiscount() + " to use this coupon");
        }

        return switch (coupon.getDiscountType()) {
            case PERCENTAGE -> {
                BigDecimal calculated = computeDiscountAmount(DiscountType.PERCENTAGE, coupon.getDiscountValue(), orderSubtotal);
                yield coupon.getMaximumDiscountAmount() != null
                        ? calculated.min(coupon.getMaximumDiscountAmount())
                        : calculated;
            }
            // API-04 decision: maximumDiscountAmount is a cap for PERCENTAGE coupons only. A FLAT coupon already is a
            // fixed amount (bounded by the subtotal), so the field is ignored for it (and stored null on write).
            case FLAT -> computeDiscountAmount(DiscountType.FLAT, coupon.getDiscountValue(), orderSubtotal);
        };
    }

    /**
     * Pure percentage/flat discount math, shared by coupons and product/shop-wide discounts.
     * Returns the discount amount (never greater than {@code base}), not the final price.
     */
    public static BigDecimal computeDiscountAmount(DiscountType type, BigDecimal value, BigDecimal base) {
        return switch (type) {
            case PERCENTAGE -> base
                    .multiply(value)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .min(base);
            case FLAT -> value.min(base);
        };
    }

    // API-04 rework: the usage counter is no longer advanced here (a read-modify-write of the entity under a row lock
    // held across the Paystack call). CouponRedemptionService owns it with atomic conditional UPDATEs.
}

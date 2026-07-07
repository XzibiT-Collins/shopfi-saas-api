package com.example.perfume_budget.utils;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DiscountCalculationUtil {
    private final CouponRepository couponRepository;

    public void checkIfCouponIsValid(Coupon coupon) {
        LocalDate now = LocalDateTime.now().toLocalDate();

        if (!Boolean.TRUE.equals(coupon.getIsActive())) {
            throw new BadRequestException("Coupon is inactive");
        }

        if (now.isBefore(coupon.getStartDate())) {
            throw new BadRequestException("Coupon is not yet active");
        }

        if (now.isAfter(coupon.getExpirationDate())) {
            throw new BadRequestException("Coupon has expired");
        }

        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            throw new BadRequestException("Coupon usage limit has been reached");
        }
    }

    public BigDecimal applyDiscount(Coupon coupon, BigDecimal orderSubtotal) {
        BigDecimal discount = calculateDiscount(coupon, orderSubtotal);
        updateCouponUsage(coupon);
        return orderSubtotal.subtract(discount).max(BigDecimal.ZERO);
    }

    public BigDecimal calculateDiscount(Coupon coupon, BigDecimal orderSubtotal) {
        if (coupon.getMinimumCartAmountForDiscount() != null &&
                orderSubtotal.compareTo(coupon.getMinimumCartAmountForDiscount()) < 0) {
            throw new BadRequestException("Order total must be at least " +
                    coupon.getMinimumCartAmountForDiscount() + " to use this coupon");
        }

        return switch (coupon.getDiscountType()) {
            case PERCENTAGE -> {
                BigDecimal calculated = computeDiscountAmount(DiscountType.PERCENTAGE, coupon.getDiscountValue(), orderSubtotal);
                yield coupon.getMaximumDiscountAmount() != null
                        ? calculated.min(coupon.getMaximumDiscountAmount())
                        : calculated;
            }
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

    private void updateCouponUsage(Coupon coupon) {
        coupon.setUsageCount(coupon.getUsageCount() + 1);

        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            coupon.setIsActive(false);
        }

        couponRepository.save(coupon);
    }
}

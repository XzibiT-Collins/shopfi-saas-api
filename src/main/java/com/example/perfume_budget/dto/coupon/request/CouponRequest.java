package com.example.perfume_budget.dto.coupon.request;

import com.example.perfume_budget.enums.DiscountType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create / full-replace payload for a coupon (API-04).
 *
 * <ul>
 *   <li>{@code couponCode}: optional (omit/null to have one generated). When present it must be 3-32 characters of
 *       letters, digits, {@code _} or {@code -}; it is trimmed and UPPER-CASED before it is stored, because checkout
 *       looks codes up that way. Blank is rejected. On update it cannot change.</li>
 *   <li>{@code discountValue} &gt; 0, at most 2 decimals; a PERCENTAGE value is at most 100.</li>
 *   <li>{@code maximumDiscountAmount}: cap for PERCENTAGE coupons (optional, &gt; 0 when sent). Ignored for FLAT.</li>
 *   <li>{@code usageLimit} 1..1,000,000; {@code perUserUsageLimit} optional 1..usageLimit (enforced at checkout).</li>
 *   <li>{@code expirationDate} must not be before {@code startDate} (the coupon is valid on both days).</li>
 * </ul>
 */
public record CouponRequest(
        @Pattern(regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9_-]{2,31}\\s*$",
                message = "Coupon code must be 3-32 characters: letters, digits, '_' or '-'")
        String couponCode,
        @NotNull DiscountType discountType,
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "Discount value must be greater than zero")
        @Digits(integer = 10, fraction = 2, message = "Discount value allows at most 2 decimal places")
        BigDecimal discountValue,
        @DecimalMin(value = "0", message = "Maximum discount amount cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "Maximum discount amount allows at most 2 decimal places")
        BigDecimal maximumDiscountAmount,
        @DecimalMin(value = "0", message = "Minimum cart amount cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "Minimum cart amount allows at most 2 decimal places")
        BigDecimal minimumCartAmountForDiscount,
        @NotNull
        @Min(value = 1, message = "Usage limit must be at least 1")
        @Max(value = 1_000_000, message = "Usage limit cannot exceed 1000000")
        Integer usageLimit,
        @NotNull Boolean isActive,
        @NotNull LocalDate startDate,
        @NotNull LocalDate expirationDate,
        @Min(value = 1, message = "Per-user usage limit must be at least 1")
        @Max(value = 1_000_000, message = "Per-user usage limit cannot exceed 1000000")
        Integer perUserUsageLimit
) {
    @JsonIgnore
    @AssertTrue(message = "Percentage discount cannot exceed 100")
    public boolean isPercentageWithinRange() {
        return discountType != DiscountType.PERCENTAGE || discountValue == null
                || discountValue.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    @JsonIgnore
    @AssertTrue(message = "Maximum discount amount must be greater than zero when set")
    public boolean isPercentageCapPositive() {
        return discountType != DiscountType.PERCENTAGE || maximumDiscountAmount == null
                || maximumDiscountAmount.signum() > 0;
    }

    @JsonIgnore
    @AssertTrue(message = "Expiration date cannot be before the start date")
    public boolean isExpirationNotBeforeStart() {
        return startDate == null || expirationDate == null || !expirationDate.isBefore(startDate);
    }

    @JsonIgnore
    @AssertTrue(message = "Per-user usage limit cannot exceed the total usage limit")
    public boolean isPerUserLimitWithinTotal() {
        return perUserUsageLimit == null || usageLimit == null || perUserUsageLimit <= usageLimit;
    }
}

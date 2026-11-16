package com.example.perfume_budget.coupon;

import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.enums.DiscountType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** API-04: every rule of the coupon request, one violated at a time. */
class CouponRequestValidationTest {

    private static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private static CouponRequest ok() {
        return new CouponRequest("SAVE10", DiscountType.PERCENTAGE, new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("0"), 100, true, TODAY, TODAY.plusDays(30), null);
    }

    private static Set<String> violations(CouponRequest r) {
        return V.validate(r).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void validRequestHasNoViolations() {
        assertThat(violations(ok())).isEmpty();
    }

    @Test
    void validBoundaries() {
        CouponRequest r = new CouponRequest(null, DiscountType.PERCENTAGE, new BigDecimal("100"), null, null, 1, false,
                TODAY, TODAY, 1);
        assertThat(violations(r)).isEmpty();
        assertThat(violations(new CouponRequest("A-1", DiscountType.FLAT, new BigDecimal("0.01"), null, new BigDecimal("0"),
                1_000_000, true, TODAY, TODAY.plusDays(1), 1_000_000))).isEmpty();
    }

    @Test
    void discountValueMustBePositive() {
        for (String v : new String[]{"0", "-1", "-0.01"}) {
            CouponRequest r = new CouponRequest("SAVE10", DiscountType.FLAT, new BigDecimal(v), null, null, 5, true, TODAY, TODAY, null);
            assertThat(violations(r)).contains("Discount value must be greater than zero");
        }
    }

    @Test
    void discountValueMaxTwoDecimals() {
        CouponRequest r = new CouponRequest("SAVE10", DiscountType.FLAT, new BigDecimal("1.234"), null, null, 5, true, TODAY, TODAY, null);
        assertThat(violations(r)).contains("Discount value allows at most 2 decimal places");
    }

    @Test
    void percentageAbove100Rejected_flatAbove100Fine() {
        CouponRequest pct = new CouponRequest("SAVE10", DiscountType.PERCENTAGE, new BigDecimal("100.01"), null, null, 5, true, TODAY, TODAY, null);
        assertThat(violations(pct)).containsExactly("Percentage discount cannot exceed 100");
        CouponRequest flat = new CouponRequest("SAVE10", DiscountType.FLAT, new BigDecimal("250"), null, null, 5, true, TODAY, TODAY, null);
        assertThat(violations(flat)).isEmpty();
    }

    @Test
    void expiryBeforeStartRejected() {
        CouponRequest r = new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY.minusDays(1), null);
        assertThat(violations(r)).containsExactly("Expiration date cannot be before the start date");
    }

    @Test
    void usageLimitBounds() {
        for (int bad : new int[]{0, -1, 1_000_001}) {
            CouponRequest r = new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, bad, true, TODAY, TODAY, null);
            assertThat(violations(r)).as("usageLimit %d", bad).isNotEmpty();
        }
    }

    @Test
    void perUserLimitBoundsAndRelation() {
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, 0)))
                .contains("Per-user usage limit must be at least 1");
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, 6)))
                .containsExactly("Per-user usage limit cannot exceed the total usage limit");
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, 1_000_001)))
                .contains("Per-user usage limit cannot exceed 1000000");
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, 5))).isEmpty();
    }

    @Test
    void capAndMinimumRules() {
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.PERCENTAGE, BigDecimal.TEN, new BigDecimal("-1"), null, 5, true, TODAY, TODAY, null)))
                .contains("Maximum discount amount cannot be negative");
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.PERCENTAGE, BigDecimal.TEN, BigDecimal.ZERO, null, 5, true, TODAY, TODAY, null)))
                .containsExactly("Maximum discount amount must be greater than zero when set");
        // FLAT ignores the cap, so a legacy client sending 0 is not rejected
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.TEN, BigDecimal.ZERO, null, 5, true, TODAY, TODAY, null))).isEmpty();
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.TEN, null, new BigDecimal("-0.01"), 5, true, TODAY, TODAY, null)))
                .contains("Minimum cart amount cannot be negative");
        assertThat(violations(new CouponRequest("SAVE10", DiscountType.FLAT, BigDecimal.TEN, null, new BigDecimal("1.234"), 5, true, TODAY, TODAY, null)))
                .contains("Minimum cart amount allows at most 2 decimal places");
    }

    @Test
    void requiredFields() {
        CouponRequest r = new CouponRequest("SAVE10", null, null, null, null, null, null, null, null, null);
        assertThat(V.validate(r)).extracting(v -> v.getPropertyPath().toString())
                .contains("discountType", "discountValue", "usageLimit", "isActive", "startDate", "expirationDate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab", "A B C", "SAVE!", "-ABC", "_ABC", "ÄBC12", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "SAVE\n10"})
    void badCodesRejected(String code) {
        CouponRequest r = new CouponRequest(code, DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, null);
        assertThat(violations(r)).as("code [%s]", code).contains("Coupon code must be 3-32 characters: letters, digits, '_' or '-'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "SAVE10", " save-10 ", "A_B", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "9LIVES"})
    void goodCodesAccepted(String code) {
        CouponRequest r = new CouponRequest(code, DiscountType.FLAT, BigDecimal.ONE, null, null, 5, true, TODAY, TODAY, null);
        assertThat(violations(r)).isEmpty();
    }
}

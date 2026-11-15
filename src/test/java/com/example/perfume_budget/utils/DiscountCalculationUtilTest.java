package com.example.perfume_budget.utils;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** API-04: coupon dates come from the injected Clock; FLAT ignores maximumDiscountAmount (documented decision). */
class DiscountCalculationUtilTest {

    private final CouponRepository repo = mock(CouponRepository.class);

    private static Clock clockAt(String instant, String zone) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of(zone));
    }

    private DiscountCalculationUtil util(Clock clock) {
        return new DiscountCalculationUtil(repo, clock);
    }

    private static Coupon coupon(DiscountType t, String value, String max, String min) {
        return Coupon.builder().id(1L).code("C").discountType(t).discountValue(new BigDecimal(value))
                .maximumDiscountAmount(max == null ? null : new BigDecimal(max))
                .minimumCartAmountForDiscount(min == null ? null : new BigDecimal(min))
                .usageLimit(10).usageCount(0).isActive(true)
                .startDate(LocalDate.of(2026, 6, 10)).expirationDate(LocalDate.of(2026, 6, 20)).build();
    }

    private static Reason reasonOf(Runnable r) {
        try {
            r.run();
        } catch (CouponRejectedException e) {
            return e.getReason();
        }
        return null;
    }

    @Test
    void dateIsTakenFromTheClockZone_notTheJvmZone() {
        Coupon c = coupon(DiscountType.FLAT, "5", null, null);
        // 2026-06-20T23:30Z is still June 20 in Accra/UTC but already June 21 in Auckland
        assertThat(reasonOf(() -> util(clockAt("2026-06-20T23:30:00Z", "UTC")).checkIfCouponIsValid(c))).isNull();
        assertThat(reasonOf(() -> util(clockAt("2026-06-20T23:30:00Z", "Pacific/Auckland")).checkIfCouponIsValid(c)))
                .isEqualTo(Reason.EXPIRED);
        // 2026-06-09T23:30Z is June 10 in Auckland (valid) but June 9 in UTC (not started)
        assertThat(reasonOf(() -> util(clockAt("2026-06-09T23:30:00Z", "Pacific/Auckland")).checkIfCouponIsValid(c))).isNull();
        assertThat(reasonOf(() -> util(clockAt("2026-06-09T23:30:00Z", "UTC")).checkIfCouponIsValid(c)))
                .isEqualTo(Reason.NOT_STARTED);
    }

    @Test
    void startAndExpiryDaysAreInclusive() {
        Coupon c = coupon(DiscountType.FLAT, "5", null, null);
        assertThat(reasonOf(() -> util(clockAt("2026-06-10T00:00:00Z", "UTC")).checkIfCouponIsValid(c))).isNull();
        assertThat(reasonOf(() -> util(clockAt("2026-06-20T23:59:59Z", "UTC")).checkIfCouponIsValid(c))).isNull();
        assertThat(reasonOf(() -> util(clockAt("2026-06-09T23:59:59Z", "UTC")).checkIfCouponIsValid(c))).isEqualTo(Reason.NOT_STARTED);
        assertThat(reasonOf(() -> util(clockAt("2026-06-21T00:00:00Z", "UTC")).checkIfCouponIsValid(c))).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void inactiveAndUsedUpAreRejected() {
        DiscountCalculationUtil u = util(clockAt("2026-06-15T10:00:00Z", "UTC"));
        Coupon off = coupon(DiscountType.FLAT, "5", null, null);
        off.setIsActive(false);
        assertThat(reasonOf(() -> u.checkIfCouponIsValid(off))).isEqualTo(Reason.INACTIVE);
        Coupon nullActive = coupon(DiscountType.FLAT, "5", null, null);
        nullActive.setIsActive(null);
        assertThat(reasonOf(() -> u.checkIfCouponIsValid(nullActive))).isEqualTo(Reason.INACTIVE);
        Coupon used = coupon(DiscountType.FLAT, "5", null, null);
        used.setUsageCount(10);
        assertThat(reasonOf(() -> u.checkIfCouponIsValid(used))).isEqualTo(Reason.USAGE_LIMIT);
        used.setUsageCount(9);
        assertThat(reasonOf(() -> u.checkIfCouponIsValid(used))).isNull();
    }

    @Test
    void flatCouponIgnoresMaximumDiscountAmount() {
        DiscountCalculationUtil u = util(Clock.systemUTC());
        // a stale/legacy cap of 5 on a FLAT 20 coupon must not change what is charged
        assertThat(u.calculateDiscount(coupon(DiscountType.FLAT, "20", "5", null), new BigDecimal("100")))
                .isEqualByComparingTo("20");
        // flat never exceeds the subtotal
        assertThat(u.calculateDiscount(coupon(DiscountType.FLAT, "20", null, null), new BigDecimal("15")))
                .isEqualByComparingTo("15");
    }

    @Test
    void percentageCouponHonoursTheCap() {
        DiscountCalculationUtil u = util(Clock.systemUTC());
        assertThat(u.calculateDiscount(coupon(DiscountType.PERCENTAGE, "50", "30", null), new BigDecimal("100")))
                .isEqualByComparingTo("30");
        assertThat(u.calculateDiscount(coupon(DiscountType.PERCENTAGE, "50", "80", null), new BigDecimal("100")))
                .isEqualByComparingTo("50");
        assertThat(u.calculateDiscount(coupon(DiscountType.PERCENTAGE, "50", null, null), new BigDecimal("100")))
                .isEqualByComparingTo("50");
    }

    @Test
    void minimumCartAmountEnforced_boundaryInclusive() {
        DiscountCalculationUtil u = util(Clock.systemUTC());
        Coupon c = coupon(DiscountType.FLAT, "5", null, "50");
        assertThat(u.calculateDiscount(c, new BigDecimal("50"))).isEqualByComparingTo("5");
        assertThatThrownBy(() -> u.calculateDiscount(c, new BigDecimal("49.99")))
                .isInstanceOf(CouponRejectedException.class);
    }

    @Test
    void recordUsageIncrementsAndDeactivatesAtTheLimit() {
        DiscountCalculationUtil u = util(Clock.systemUTC());
        Coupon c = coupon(DiscountType.FLAT, "5", null, null);
        c.setUsageCount(8);
        u.recordCouponUsage(c);
        assertThat(c.getUsageCount()).isEqualTo(9);
        assertThat(c.getIsActive()).isTrue();
        u.recordCouponUsage(c);
        assertThat(c.getUsageCount()).isEqualTo(10);
        assertThat(c.getIsActive()).isFalse();
        verify(repo, org.mockito.Mockito.times(2)).save(c);
    }
}

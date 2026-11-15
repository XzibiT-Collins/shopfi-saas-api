package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.TaxRepository;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** API-04 rework: GET /tax?couponCode= applies the per-customer limit like the cart preview and checkout. */
class TaxServicePerUserLimitTest {

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final TaxRepository taxes = mock(TaxRepository.class);
    private final AuthUserUtil auth = mock(AuthUserUtil.class);
    private final TaxServiceImpl service = new TaxServiceImpl(taxes, new DiscountCalculationUtil(coupons, Clock.systemUTC()),
            coupons, new CouponPerUserLimit(orders), auth);

    private Coupon coupon() {
        return Coupon.builder().id(3L).code("ONCE").discountType(com.example.perfume_budget.enums.DiscountType.FLAT)
                .discountValue(new BigDecimal("5")).isActive(true).usageCount(0).usageLimit(10).perUserUsageLimit(1)
                .startDate(LocalDate.now().minusDays(1)).expirationDate(LocalDate.now().plusDays(1)).build();
    }

    @Test
    void customerAtTheirLimitIsRejected_othersGetTheDiscount() {
        when(coupons.findByCode("ONCE")).thenReturn(Optional.of(coupon()));
        when(taxes.findAllByIsActiveTrueOrderByApplyOrderAsc()).thenReturn(List.of());
        User used = User.builder().id(1L).build();
        User fresh = User.builder().id(2L).build();
        when(orders.countCouponRedemptionsByUser(1L, 3L)).thenReturn(1L);
        when(orders.countCouponRedemptionsByUser(2L, 3L)).thenReturn(0L);

        when(auth.getCurrentUser()).thenReturn(used);
        assertThatThrownBy(() -> service.calculateTaxesAtCheckout(new BigDecimal("100"), "once"))
                .isInstanceOf(CouponRejectedException.class).hasMessage(CouponPerUserLimit.MESSAGE);

        when(auth.getCurrentUser()).thenReturn(fresh);
        assertThat(service.calculateTaxesAtCheckout(new BigDecimal("100"), "once").discount()).isEqualByComparingTo("5");
    }
}

package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.model.Coupon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CouponMapperTest {

    @Test
    void toCoupon_Success() {
        CouponRequest request = new CouponRequest(
                "PROMO10", DiscountType.FLAT, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 5, true, LocalDate.now(), LocalDate.now().plusDays(1)
        );

        Coupon result = CouponMapper.toCoupon(request);

        assertNotNull(result);
        assertEquals(DiscountType.FLAT, result.getDiscountType());
        assertEquals(BigDecimal.TEN, result.getDiscountValue());
        assertEquals(5, result.getUsageLimit());
        assertTrue(result.getIsActive());
        assertEquals(0, result.getUsageCount());
    }

    @Test
    void toCouponDetailResponse_Success() {
        Coupon coupon = Coupon.builder()
                .id(1L).code("CODE").isActive(true).discountType(DiscountType.PERCENTAGE)
                .usageLimit(10).usageCount(2).discountValue(BigDecimal.valueOf(5))
                .startDate(LocalDate.now()).expirationDate(LocalDate.now().plusDays(5))
                .build();

        CouponDetailResponse response = CouponMapper.toCouponDetailResponse(coupon);

        assertNotNull(response);
        assertEquals(1L, response.couponId());
        assertEquals("CODE", response.couponCode());
        assertEquals(2, response.usageCount());
    }

    @Test
    void toCouponListResponse_Success() {
        Coupon coupon = Coupon.builder()
                .id(1L).code("CODE").isActive(true).discountType(DiscountType.PERCENTAGE)
                .usageLimit(10).usageCount(2).expirationDate(LocalDate.now().plusDays(5))
                .build();

        CouponListResponse response = CouponMapper.toCouponListResponse(coupon);

        assertNotNull(response);
        assertEquals(1L, response.couponId());
        assertEquals("CODE", response.couponCode());
    }

    @Test
    void toCoupon_NullInput_ThrowsException() {
        assertThrows(NullPointerException.class, () -> CouponMapper.toCoupon(null));
    }
}

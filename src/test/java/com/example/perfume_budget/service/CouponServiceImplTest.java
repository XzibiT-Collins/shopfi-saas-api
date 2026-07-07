package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.utils.CouponCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponCodeGenerator couponCodeGenerator;

    @InjectMocks
    private CouponServiceImpl couponService;

    private Coupon testCoupon;
    private CouponRequest couponRequest;

    @BeforeEach
    void setUp() {
        testCoupon = Coupon.builder()
                .id(1L)
                .code("PROMO10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10.00"))
                .startDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(10))
                .isActive(true)
                .usageCount(0)
                .build();

        couponRequest = new CouponRequest(
                "PROMO10",
                DiscountType.PERCENTAGE,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                10,
                true,
                LocalDate.now(),
                LocalDate.now().plusDays(10)
        );
    }

    @Test
    void createCoupon_Success_WithProvidedCode() {
        when(couponRepository.save(any(Coupon.class))).thenReturn(testCoupon);

        CouponDetailResponse result = couponService.createCoupon(couponRequest);

        assertNotNull(result);
        assertEquals("PROMO10", result.couponCode());
        verify(couponCodeGenerator, never()).generateCouponCode();
    }

    @Test
    void createCoupon_Success_WithGeneratedCode() {
        CouponRequest requestNoCode = new CouponRequest(
                null,
                DiscountType.PERCENTAGE,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                10,
                true,
                LocalDate.now(),
                LocalDate.now().plusDays(10)
        );
        when(couponCodeGenerator.generateCouponCode()).thenReturn("GEN-123");
        when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> {
            Coupon c = invocation.getArgument(0);
            c.setId(2L);
            return c;
        });

        CouponDetailResponse result = couponService.createCoupon(requestNoCode);

        assertNotNull(result);
        assertEquals("GEN-123", result.couponCode());
        verify(couponCodeGenerator).generateCouponCode();
    }

    @Test
    void getCouponById_Success() {
        when(couponRepository.findById(1L)).thenReturn(Optional.of(testCoupon));

        CouponDetailResponse result = couponService.getCouponById(1L);

        assertNotNull(result);
        assertEquals(1L, result.couponId());
    }

    @Test
    void getCouponById_Failure_NotFound() {
        when(couponRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> couponService.getCouponById(1L));
    }

    @Test
    void getAllCoupons_Success() {
        Page<Coupon> couponPage = new PageImpl<>(List.of(testCoupon));
        when(couponRepository.findAll(any(Pageable.class))).thenReturn(couponPage);

        PageResponse<CouponListResponse> result = couponService.getAllCoupons(Pageable.unpaged());

        assertNotNull(result);
        assertEquals(1, result.content().size());
    }
}

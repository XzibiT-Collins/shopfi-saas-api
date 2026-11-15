package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.utils.CouponCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

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
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private CouponServiceImpl couponService;

    private Coupon testCoupon;

    private static CouponRequest req(String code, DiscountType type, String max, int usageLimit, Integer perUser, boolean active) {
        return new CouponRequest(code, type, new BigDecimal("10.00"), max == null ? null : new BigDecimal(max),
                new BigDecimal("50.00"), usageLimit, active, LocalDate.now(), LocalDate.now().plusDays(10), perUser);
    }

    @BeforeEach
    void setUp() {
        testCoupon = Coupon.builder()
                .id(1L)
                .code("PROMO10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10.00"))
                .startDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(10))
                .usageLimit(10)
                .isActive(true)
                .usageCount(0)
                .build();
    }

    // ---- create

    @Test
    void createCoupon_normalisesProvidedCodeToUpperCaseTrimmed() {
        when(couponRepository.existsByCode("SUMMER-1")).thenReturn(false);
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));

        CouponDetailResponse result = couponService.createCoupon(req("  summer-1 ", DiscountType.PERCENTAGE, "100.00", 10, null, true));

        assertEquals("SUMMER-1", result.couponCode());
        verify(couponCodeGenerator, never()).generateCouponCode();
    }

    @Test
    void createCoupon_turkishLocaleDoesNotBreakNormalisation() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("ILLEGAL1", CouponServiceImpl.normaliseCode("illegal1"));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }

    @Test
    void createCoupon_generatesCodeWhenAbsent() {
        when(couponCodeGenerator.generateCouponCode()).thenReturn("GEN123");
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals("GEN123", couponService.createCoupon(req(null, DiscountType.PERCENTAGE, "100.00", 10, null, true)).couponCode());
    }

    @Test
    void createCoupon_duplicateCode_is409WithFixedCode_andNothingSaved() {
        when(couponRepository.existsByCode("PROMO10")).thenReturn(true);

        ApiCodeException ex = assertThrows(ApiCodeException.class,
                () -> couponService.createCoupon(req("promo10", DiscountType.PERCENTAGE, "100.00", 10, null, true)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("COUPON_CODE_EXISTS", ex.getCode());
        assertEquals("A coupon with this code already exists.", ex.getMessage());
        verify(couponRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCoupon_racedDuplicate_isTheSame409() {
        when(couponRepository.existsByCode("PROMO10")).thenReturn(false);
        when(couponRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uc_coupons_code"));

        ApiCodeException ex = assertThrows(ApiCodeException.class,
                () -> couponService.createCoupon(req("PROMO10", DiscountType.PERCENTAGE, "100.00", 10, null, true)));
        assertEquals("COUPON_CODE_EXISTS", ex.getCode());
    }

    @Test
    void createCoupon_flatCouponStoresNoCap_percentageKeepsIt_perUserLimitIsStored() {
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));
        when(couponCodeGenerator.generateCouponCode()).thenReturn("A1B2C3");

        CouponDetailResponse flat = couponService.createCoupon(req(null, DiscountType.FLAT, "99.00", 10, 2, true));
        assertNull(flat.maximumDiscountAmount());
        assertEquals(2, flat.perUserUsageLimit());
        assertEquals(0, flat.usageCount());

        CouponDetailResponse pct = couponService.createCoupon(req(null, DiscountType.PERCENTAGE, "99.00", 10, null, true));
        assertEquals(new BigDecimal("99.00"), pct.maximumDiscountAmount());
        assertNull(pct.perUserUsageLimit());
    }

    // ---- read

    @Test
    void getCouponById_Success() {
        when(couponRepository.findById(1L)).thenReturn(Optional.of(testCoupon));
        assertEquals(1L, couponService.getCouponById(1L).couponId());
    }

    @Test
    void getCouponById_Failure_NotFound() {
        when(couponRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> couponService.getCouponById(1L));
    }

    @Test
    void getAllCoupons_includesDiscountDetails() {
        testCoupon.setMaximumDiscountAmount(new BigDecimal("30"));
        testCoupon.setPerUserUsageLimit(3);
        Page<Coupon> couponPage = new PageImpl<>(List.of(testCoupon));
        when(couponRepository.findAll(any(Pageable.class))).thenReturn(couponPage);

        PageResponse<CouponListResponse> result = couponService.getAllCoupons(Pageable.unpaged());

        assertEquals(1, result.content().size());
        CouponListResponse c = result.content().get(0);
        assertEquals(new BigDecimal("10.00"), c.discountValue());
        assertEquals(new BigDecimal("30"), c.maximumDiscountAmount());
        assertEquals(3, c.perUserUsageLimit());
        assertEquals(LocalDate.now(), c.startDate());
    }

    // ---- update

    @Test
    void updateCoupon_replacesEditableFields_codeUntouched() {
        testCoupon.setUsageCount(2);
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));

        CouponDetailResponse r = couponService.updateCoupon(1L, req(" promo10", DiscountType.FLAT, "5.00", 2, 1, false));

        assertEquals("PROMO10", r.couponCode());
        assertEquals(DiscountType.FLAT, r.discountType());
        assertNull(r.maximumDiscountAmount());
        assertEquals(2, r.usageLimit());
        assertEquals(1, r.perUserUsageLimit());
        assertFalse(r.isActive());
        assertEquals(2, r.usageCount());
    }

    @Test
    void updateCoupon_codeChangeRejected() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        assertThrows(BadRequestException.class,
                () -> couponService.updateCoupon(1L, req("OTHER", DiscountType.PERCENTAGE, "1", 10, null, true)));
        verify(couponRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateCoupon_omittedCodeIsAllowed() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals("PROMO10", couponService.updateCoupon(1L, req(null, DiscountType.PERCENTAGE, "1", 10, null, true)).couponCode());
    }

    @Test
    void updateCoupon_usageLimitBelowRedemptions_rejected_equalAllowed() {
        testCoupon.setUsageCount(5);
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        assertThrows(BadRequestException.class,
                () -> couponService.updateCoupon(1L, req(null, DiscountType.PERCENTAGE, "1", 4, null, true)));
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals(5, couponService.updateCoupon(1L, req(null, DiscountType.PERCENTAGE, "1", 5, null, true)).usageLimit());
    }

    @Test
    void updateCoupon_notFound() {
        when(couponRepository.lockById(9L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> couponService.updateCoupon(9L, req(null, DiscountType.PERCENTAGE, "1", 10, null, true)));
    }

    // ---- deactivate

    @Test
    void deactivate_locksAndFlushesInactive_idempotent() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(couponRepository.saveAndFlush(any(Coupon.class))).thenAnswer(i -> i.getArgument(0));

        assertFalse(couponService.deactivateCoupon(1L).isActive());
        assertFalse(couponService.deactivateCoupon(1L).isActive());
        verify(couponRepository, never()).findById(any());
    }

    @Test
    void deactivate_notFound() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> couponService.deactivateCoupon(1L));
    }

    // ---- delete

    @Test
    void delete_neverRedeemed_deletes() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(orderRepository.existsByCouponId(1L)).thenReturn(false);

        couponService.deleteCoupon(1L);

        ArgumentCaptor<Coupon> c = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).delete(c.capture());
        assertEquals(1L, c.getValue().getId());
        verify(couponRepository).flush();
    }

    @Test
    void delete_redeemedByCounter_is409() {
        testCoupon.setUsageCount(1);
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        ApiCodeException ex = assertThrows(ApiCodeException.class, () -> couponService.deleteCoupon(1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("COUPON_REDEEMED", ex.getCode());
        verify(couponRepository, never()).delete(any());
    }

    @Test
    void delete_referencedByAnOrderEvenWithZeroCounter_is409() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(orderRepository.existsByCouponId(1L)).thenReturn(true);
        assertThrows(ApiCodeException.class, () -> couponService.deleteCoupon(1L));
        verify(couponRepository, never()).delete(any());
    }

    @Test
    void delete_nullUsageCountTreatedAsZero() {
        testCoupon.setUsageCount(null);
        when(couponRepository.lockById(1L)).thenReturn(Optional.of(testCoupon));
        when(orderRepository.existsByCouponId(1L)).thenReturn(false);
        couponService.deleteCoupon(1L);
        verify(couponRepository).delete(testCoupon);
    }

    @Test
    void delete_notFound() {
        when(couponRepository.lockById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> couponService.deleteCoupon(1L));
    }
}

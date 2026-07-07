package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxAndDiscountCalculationResponse;
import com.example.perfume_budget.dto.tax.response.TaxResponse;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.Tax;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.TaxRepository;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxServiceImplTest {

    @Mock
    private TaxRepository taxRepository;

    @Mock
    private DiscountCalculationUtil discountCalculationUtil;

    @Mock
    private CouponRepository couponRepository;

    @InjectMocks
    private TaxServiceImpl taxService;

    private Tax testTax;
    private TaxRequest taxRequest;
    private Coupon testCoupon;

    @BeforeEach
    void setUp() {
        testTax = Tax.builder()
                .id(1L)
                .name("VAT")
                .code("VAT")
                .rate(new BigDecimal("15.0"))
                .isActive(true)
                .isCompound(false)
                .applyOrder(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        taxRequest = new TaxRequest("VAT", "VAT", new BigDecimal("15.0"), true, false, 1);

        testCoupon = Coupon.builder()
                .id(1L)
                .code("SAVE10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10"))
                .isActive(true)
                .startDate(LocalDate.now().minusDays(1))
                .expirationDate(LocalDate.now().plusDays(30))
                .usageLimit(100)
                .usageCount(0)
                .build();
    }

    @Test
    void createTax_Success() {
        when(taxRepository.existsByName("VAT")).thenReturn(false);
        when(taxRepository.existsByCode("VAT")).thenReturn(false);
        when(taxRepository.save(any(Tax.class))).thenReturn(testTax);

        TaxResponse result = taxService.createTax(taxRequest);

        assertNotNull(result);
        assertEquals("VAT", result.name());
        verify(taxRepository).save(any(Tax.class));
    }

    @Test
    void createTax_Failure_NameExists() {
        when(taxRepository.existsByName("VAT")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> taxService.createTax(taxRequest));
    }

    @Test
    void createTax_Failure_CodeExists() {
        when(taxRepository.existsByName("VAT")).thenReturn(false);
        when(taxRepository.existsByCode("VAT")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> taxService.createTax(taxRequest));
    }

    @Test
    void getTaxById_Success() {
        when(taxRepository.findById(1L)).thenReturn(Optional.of(testTax));

        TaxResponse result = taxService.getTaxById(1L);

        assertNotNull(result);
        assertEquals(1L, result.id());
    }

    @Test
    void getTaxById_Failure_NotFound() {
        when(taxRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> taxService.getTaxById(1L));
    }

    @Test
    void getAllTaxes_Success() {
        when(taxRepository.findAll()).thenReturn(List.of(testTax));

        List<TaxResponse> result = taxService.getAllTaxes();

        assertEquals(1, result.size());
    }

    @Test
    void updateTax_Success() {
        TaxRequest updateRequest = new TaxRequest("New VAT", "NVAT", new BigDecimal("17.5"), true, false, 1);
        when(taxRepository.findById(1L)).thenReturn(Optional.of(testTax));
        when(taxRepository.existsByName("New VAT")).thenReturn(false);
        when(taxRepository.existsByCode("NVAT")).thenReturn(false);
        when(taxRepository.save(any(Tax.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaxResponse result = taxService.updateTax(1L, updateRequest);

        assertNotNull(result);
        assertEquals("New VAT", result.name());
        assertEquals("NVAT", result.code());
        assertEquals(new BigDecimal("17.5"), result.rate());
    }

    @Test
    void deleteTax_Success() {
        when(taxRepository.existsById(1L)).thenReturn(true);

        taxService.deleteTax(1L);

        verify(taxRepository).deleteById(1L);
    }

    @Test
    void deleteTax_Failure_NotFound() {
        when(taxRepository.existsById(1L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> taxService.deleteTax(1L));
    }

    @Test
    void calculateTaxesAtCheckout_NullCouponCode_ReturnsZeroDiscount() {
        when(taxRepository.findAllByIsActiveTrueOrderByApplyOrderAsc()).thenReturn(List.of());

        TaxAndDiscountCalculationResponse result = taxService.calculateTaxesAtCheckout(new BigDecimal("100.00"), null);

        assertEquals(BigDecimal.ZERO, result.discount());
        verifyNoInteractions(couponRepository, discountCalculationUtil);
    }

    @Test
    void calculateTaxesAtCheckout_BlankCouponCode_ReturnsZeroDiscount() {
        when(taxRepository.findAllByIsActiveTrueOrderByApplyOrderAsc()).thenReturn(List.of());

        TaxAndDiscountCalculationResponse result = taxService.calculateTaxesAtCheckout(new BigDecimal("100.00"), "   ");

        assertEquals(BigDecimal.ZERO, result.discount());
        verifyNoInteractions(couponRepository, discountCalculationUtil);
    }

    @Test
    void calculateTaxesAtCheckout_ValidCoupon_AppliesDiscount() {
        BigDecimal subtotal = new BigDecimal("100.00");
        BigDecimal discountAmount = new BigDecimal("10.00");

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(testCoupon));
        doNothing().when(discountCalculationUtil).checkIfCouponIsValid(testCoupon);
        when(discountCalculationUtil.calculateDiscount(testCoupon, subtotal)).thenReturn(discountAmount);
        when(taxRepository.findAllByIsActiveTrueOrderByApplyOrderAsc()).thenReturn(List.of());

        TaxAndDiscountCalculationResponse result = taxService.calculateTaxesAtCheckout(subtotal, "SAVE10");

        assertEquals(discountAmount, result.discount());
        verify(discountCalculationUtil).checkIfCouponIsValid(testCoupon);
        verify(discountCalculationUtil).calculateDiscount(testCoupon, subtotal);
    }

    @Test
    void calculateTaxesAtCheckout_CouponCodeStrippedAndUppercased() {
        BigDecimal subtotal = new BigDecimal("100.00");

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(testCoupon));
        doNothing().when(discountCalculationUtil).checkIfCouponIsValid(testCoupon);
        when(discountCalculationUtil.calculateDiscount(testCoupon, subtotal)).thenReturn(new BigDecimal("10.00"));
        when(taxRepository.findAllByIsActiveTrueOrderByApplyOrderAsc()).thenReturn(List.of());

        taxService.calculateTaxesAtCheckout(subtotal, "  save10  ");

        verify(couponRepository).findByCode("SAVE10");
    }

    @Test
    void calculateTaxesAtCheckout_InvalidCouponCode_ThrowsBadRequestException() {
        when(couponRepository.findByCode("INVALID")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> taxService.calculateTaxesAtCheckout(new BigDecimal("100.00"), "INVALID"));
    }

    @Test
    void calculateTaxesAtCheckout_InvalidCoupon_ThrowsBadRequestException() {
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(testCoupon));
        doThrow(new BadRequestException("Coupon is inactive"))
                .when(discountCalculationUtil).checkIfCouponIsValid(testCoupon);

        assertThrows(BadRequestException.class,
                () -> taxService.calculateTaxesAtCheckout(new BigDecimal("100.00"), "SAVE10"));
    }
}

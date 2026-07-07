package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponCodeGeneratorTest {

    @Mock
    private CouponRepository couponRepository;

    @InjectMocks
    private CouponCodeGenerator couponCodeGenerator;

    @Test
    void generateCouponCode_Success() {
        when(couponRepository.existsByCode(anyString())).thenReturn(false);

        String code = couponCodeGenerator.generateCouponCode();

        assertNotNull(code);
        assertEquals(8, code.length()); // 6 random + 2 year
        verify(couponRepository).existsByCode(anyString());
    }

    @Test
    void generateCouponCode_Failure_MaxAttemptsReached() {
        when(couponRepository.existsByCode(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> couponCodeGenerator.generateCouponCode());
        verify(couponRepository, times(5)).existsByCode(anyString());
    }
}

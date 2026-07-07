package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReferenceGeneratorTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentReferenceGenerator paymentReferenceGenerator;

    @Test
    void generateReference_Success() {
        when(paymentRepository.existsBySystemReference(anyString())).thenReturn(false);

        String reference = paymentReferenceGenerator.generateReference();

        assertNotNull(reference);
        assertTrue(reference.startsWith("TNX-"));
        verify(paymentRepository).existsBySystemReference(anyString());
    }

    @Test
    void generateReference_Failure_MaxAttemptsReached() {
        when(paymentRepository.existsBySystemReference(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> paymentReferenceGenerator.generateReference());
        verify(paymentRepository, times(5)).existsBySystemReference(anyString());
    }
}

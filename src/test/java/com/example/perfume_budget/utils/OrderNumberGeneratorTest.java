package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.OrderNumberClaimRepository;
import com.example.perfume_budget.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderNumberGeneratorTest {

    private static final Pattern FORMAT = Pattern.compile("PB-\\d{8}-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{10}");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderNumberClaimRepository claims;

    @InjectMocks
    private OrderNumberGenerator orderNumberGenerator;

    @Test
    void generateOrderNumber_Success() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);
        when(claims.claim(anyString())).thenReturn(true);

        String orderNumber = orderNumberGenerator.generateOrderNumber();

        assertTrue(FORMAT.matcher(orderNumber).matches(), orderNumber);
        verify(claims).claim(orderNumber);
    }

    @Test
    void suffixIsTenSymbolsAndDoesNotRepeatAcrossManyDraws() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);
        when(claims.claim(anyString())).thenReturn(true);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertTrue(seen.add(orderNumberGenerator.generateOrderNumber()));
        }
    }

    @Test
    void existingNumberIsSkippedAndNeverClaimed() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(true, false);
        when(claims.claim(anyString())).thenReturn(true);
        orderNumberGenerator.generateOrderNumber();
        verify(orderRepository, times(2)).existsByOrderNumber(anyString());
        verify(claims, times(1)).claim(anyString());
    }

    @Test
    void lostClaimRaceRetriesWithAnotherCandidate() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);
        when(claims.claim(anyString())).thenReturn(false, false, true);
        String n = orderNumberGenerator.generateOrderNumber();
        assertNotNull(n);
        verify(claims, times(3)).claim(anyString());
    }

    @Test
    void generateOrderNumber_Failure_MaxAttemptsReached() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> orderNumberGenerator.generateOrderNumber());
        verify(orderRepository, times(5)).existsByOrderNumber(anyString());
        verifyNoInteractions(claims);
    }

    @Test
    void claimsAlwaysLostGivesUpAfterFiveAttempts() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);
        when(claims.claim(anyString())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> orderNumberGenerator.generateOrderNumber());
        verify(claims, times(5)).claim(anyString());
    }
}

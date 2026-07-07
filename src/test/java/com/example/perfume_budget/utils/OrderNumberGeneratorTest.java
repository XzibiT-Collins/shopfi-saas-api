package com.example.perfume_budget.utils;

import com.example.perfume_budget.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderNumberGeneratorTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderNumberGenerator orderNumberGenerator;

    @Test
    void generateOrderNumber_Success() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);

        String orderNumber = orderNumberGenerator.generateOrderNumber();

        assertNotNull(orderNumber);
        assertTrue(orderNumber.startsWith("PB-"));
        verify(orderRepository).existsByOrderNumber(anyString());
    }

    @Test
    void generateOrderNumber_Failure_MaxAttemptsReached() {
        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> orderNumberGenerator.generateOrderNumber());
        verify(orderRepository, times(5)).existsByOrderNumber(anyString());
    }
}

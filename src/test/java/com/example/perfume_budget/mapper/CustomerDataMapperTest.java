package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.projection.CustomerOrderSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerDataMapperTest {

    @Mock
    private CustomerOrderSummary summary;

    @Test
    void toCustomerDataResponse_Success() {
        when(summary.getUserId()).thenReturn(1L);
        when(summary.getFullName()).thenReturn("John Doe");
        when(summary.getEmail()).thenReturn("john@test.com");
        when(summary.getTotalOrders()).thenReturn(5L);
        when(summary.getTotalAmountSpent()).thenReturn(new BigDecimal("500.00"));
        when(summary.getIsActive()).thenReturn(true);
        when(summary.getCreatedAt()).thenReturn(LocalDateTime.now());

        CustomerDataResponse response = CustomerDataMapper.toCustomerDataResponse(summary);

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("John Doe", response.fullName());
        assertTrue(response.totalSpent().contains("500.00"));
    }

    @Test
    void toCustomerDataResponse_NullCreatedAt_ReturnsNullDateJoined() {
        when(summary.getUserId()).thenReturn(1L);
        when(summary.getFullName()).thenReturn("John Doe");
        when(summary.getEmail()).thenReturn("john@test.com");
        when(summary.getTotalOrders()).thenReturn(5L);
        when(summary.getTotalAmountSpent()).thenReturn(new BigDecimal("500.00"));
        when(summary.getIsActive()).thenReturn(true);
        when(summary.getCreatedAt()).thenReturn(null);
        
        CustomerDataResponse response = CustomerDataMapper.toCustomerDataResponse(summary);
        
        assertNotNull(response);
        assertNull(response.dateJoined());
    }
}

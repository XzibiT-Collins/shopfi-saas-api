package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservedProductCleanupServiceTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 5, 11, 10, 0);

    @Mock
    private InventoryAllocationRepository inventoryAllocationRepository;
    @Mock
    private OrderLifecycleService orderLifecycleService;

    @InjectMocks
    private ReservedProductCleanupService service;

    private static InventoryAllocation alloc(String orderNumber) {
        return InventoryAllocation.builder().referenceType(InventoryReferenceType.ORDER).referenceId(orderNumber)
                .status(InventoryAllocationStatus.RESERVED).quantity(1).build();
    }

    private void stale(InventoryAllocation... a) {
        when(inventoryAllocationRepository.findByReferenceTypeAndStatusAndCreatedAtBefore(
                InventoryReferenceType.ORDER, InventoryAllocationStatus.RESERVED, CUTOFF)).thenReturn(List.of(a));
        ReflectionTestUtils.setField(service, "expiryHours", 1L);
    }

    @Test
    void expiresEachDistinctOrderOnceAndCountsOnlyCancelled() {
        stale(alloc("A"), alloc("A"), alloc("B"));
        when(orderLifecycleService.expire(eq("A"), anyString())).thenReturn(true);
        when(orderLifecycleService.expire(eq("B"), anyString())).thenReturn(false);

        assertEquals(1, service.cleanupExpiredReservations(CUTOFF));
        verify(orderLifecycleService, times(1)).expire("A", "Reservation expired after 1 hour");
        verify(orderLifecycleService, times(1)).expire(eq("B"), anyString());
    }

    @Test
    void oneFailingOrderDoesNotStopTheRest() {
        stale(alloc("A"), alloc("B"));
        when(orderLifecycleService.expire(eq("A"), anyString())).thenThrow(new IllegalStateException("boom"));
        when(orderLifecycleService.expire(eq("B"), anyString())).thenReturn(true);

        assertEquals(1, service.cleanupExpiredReservations(CUTOFF));
    }

    @Test
    void returnsZeroWhenNoStaleAllocations() {
        stale();
        assertEquals(0, service.cleanupExpiredReservations(CUTOFF));
        verifyNoInteractions(orderLifecycleService);
    }
}

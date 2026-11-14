package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStockReservationServiceTest {

    @Mock InventoryManagementService inventory;
    @Mock InventoryAllocationRepository allocations;
    @InjectMocks OrderStockReservationService service;

    private final List<OrderItem> items = List.of(OrderItem.builder().productId(1L).quantity(2).build());

    @Test
    void neverReservesTwiceWhenStillHoldingStock() {
        when(allocations.existsByReferenceTypeAndReferenceIdAndStatus(
                InventoryReferenceType.ORDER, "ORD-1", InventoryAllocationStatus.RESERVED)).thenReturn(true);
        assertTrue(service.reserveForLatePayment("ORD-1", items));
        verifyNoInteractions(inventory);
    }

    @Test
    void reservesWhenNothingIsHeld() {
        when(allocations.existsByReferenceTypeAndReferenceIdAndStatus(any(), any(), any())).thenReturn(false);
        assertTrue(service.reserveForLatePayment("ORD-1", items));
        verify(inventory).reserveOrderInventory("ORD-1", items);
    }
}

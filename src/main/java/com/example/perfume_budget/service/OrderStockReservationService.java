package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Re-reserves stock for an order whose reservation was released (failed/cancelled/expired) when its payment turns out
 * to have succeeded after all. Runs in its OWN transaction so an "insufficient stock" failure rolls back only the
 * partial reservation and does not poison the caller's transaction (which still has to record the payment).
 */
@Service
@RequiredArgsConstructor
public class OrderStockReservationService {

    private final InventoryManagementService inventoryManagementService;
    private final InventoryAllocationRepository inventoryAllocationRepository;

    /** @return true when the order now holds a full reservation; throws (and rolls back) when stock is short */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveForLatePayment(String orderNumber, List<OrderItem> items) {
        if (inventoryAllocationRepository.existsByReferenceTypeAndReferenceIdAndStatus(
                InventoryReferenceType.ORDER, orderNumber, InventoryAllocationStatus.RESERVED)) {
            return true; // still (or already) holding stock: never reserve twice
        }
        inventoryManagementService.reserveOrderInventory(orderNumber, items);
        return true;
    }
}

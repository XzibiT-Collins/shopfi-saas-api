package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Expires abandoned unpaid orders: finds order reservations older than the cutoff and cancels each order in its own
 * transaction through {@link OrderLifecycleService#expire} (row lock + status re-check, so a cancel, a webhook or a
 * second job run racing on the same order releases its stock exactly once). Runs under the tenant bound by the caller
 * ({@code ReservedProductCleanupTask} loops active tenants with {@code TenantRunner}); this class never switches tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservedProductCleanupService {

    private final InventoryAllocationRepository inventoryAllocationRepository;
    private final OrderLifecycleService orderLifecycleService;

    @Value("${inventory.reservation.expiry-hours:1}")
    private long expiryHours;

    public int cleanupExpiredReservations(LocalDateTime cutoff) {
        List<InventoryAllocation> staleReservations = inventoryAllocationRepository
                .findByReferenceTypeAndStatusAndCreatedAtBefore(
                        InventoryReferenceType.ORDER,
                        InventoryAllocationStatus.RESERVED,
                        cutoff
                );

        if (staleReservations.isEmpty()) {
            return 0;
        }

        String reason = "Reservation expired after " + expiryHours + (expiryHours == 1 ? " hour" : " hours");
        int cleanedOrders = 0;
        for (String orderNumber : staleReservations.stream()
                .map(InventoryAllocation::getReferenceId)
                .distinct()
                .toList()) {
            try {
                if (orderLifecycleService.expire(orderNumber, reason)) {
                    cleanedOrders++;
                }
            } catch (RuntimeException e) {
                // one bad order must not stop the rest of this tenant's cleanup
                log.error("Expiry failed for order {}: {}", orderNumber, e.getMessage());
            }
        }

        log.info("Cleaned up {} expired reserved order(s)", cleanedOrders);
        return cleanedOrders;
    }
}

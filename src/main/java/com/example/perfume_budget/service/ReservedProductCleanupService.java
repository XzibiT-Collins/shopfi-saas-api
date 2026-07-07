package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservedProductCleanupService {
    private static final String RESERVATION_EXPIRED_REASON = "Reservation expired after 1 hour";

    private final InventoryAllocationRepository inventoryAllocationRepository;
    private final InventoryManagementService inventoryManagementService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
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

        int cleanedOrders = 0;
        for (String orderNumber : staleReservations.stream()
                .map(InventoryAllocation::getReferenceId)
                .distinct()
                .toList()) {

            Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
            if (order == null) {
                log.warn("Skipping cleanup for unknown order {}", orderNumber);
                continue;
            }

            if (order.getStatus() == PaymentStatus.COMPLETED || order.getStatus() == PaymentStatus.CANCELLED) {
                log.debug("Skipping cleanup for order {} because it is already {}", orderNumber, order.getStatus());
                continue;
            }

            inventoryManagementService.releaseOrderInventory(orderNumber);
            cancelOrder(order);
            orderRepository.save(order);

            paymentRepository.findByOrder_OrderNumber(orderNumber)
                    .ifPresent(payment -> cancelPayment(payment, orderNumber));

            cleanedOrders++;
        }

        log.info("Cleaned up {} expired reserved order(s)", cleanedOrders);
        return cleanedOrders;
    }

    private void cancelOrder(Order order) {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
    }

    private void cancelPayment(Payment payment, String orderNumber) {
        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.CANCELLED) {
            return;
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setFailureReason(RESERVATION_EXPIRED_REASON);
        paymentRepository.save(payment);
        log.info("Cancelled payment for expired reservation on order {}", orderNumber);
    }
}

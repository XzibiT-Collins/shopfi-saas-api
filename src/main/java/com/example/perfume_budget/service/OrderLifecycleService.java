package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Cancels abandoned/unwanted unpaid orders (owner cancel and the expiry job) and releases their stock reservation.
 *
 * <p>Exactly-once: every transition takes the order's row lock first ({@code OrderRepository#lockById}) and decides
 * from the status it reads AFTER the lock. A concurrent cancel, expiry run, failure webhook or success webhook for the
 * same order therefore serialises; only the first one changes the status and releases/consumes the reservation, the
 * others see the new status and do nothing. Tenant scoping comes from the active {@code TenantContext} (request filter
 * for the owner cancel, {@code TenantRunner} for the expiry job).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    public static final String ORDER_NOT_FOUND = "Order not found.";
    static final String CANCELLED_BY_CUSTOMER = "Cancelled by customer";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryManagementService inventoryManagementService;
    private final InventoryAllocationRepository inventoryAllocationRepository;

    /**
     * Owner cancel. Same 404 for "no such order", "someone else's order" and "other tenant's order".
     * Idempotent for an already cancelled order; 409 ORDER_NOT_CANCELLABLE once paid or failed.
     */
    @Transactional
    public Order cancelByOwner(String orderNumber, User owner) {
        String clean = orderNumber == null ? "" : orderNumber.trim().toUpperCase();
        Long id = orderRepository.findIdByOrderNumberAndUser(clean, owner)
                .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));
        Order order = orderRepository.lockById(id)
                .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));

        PaymentStatus status = order.getStatus();
        if (status == PaymentStatus.CANCELLED) {
            return order; // idempotent: nothing to release a second time
        }
        if (status != PaymentStatus.PENDING && status != PaymentStatus.INITIATED) {
            throw new ApiCodeException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                    "This order can no longer be cancelled.",
                    Map.of("orderNumber", order.getOrderNumber(), "paymentStatus", String.valueOf(status)));
        }
        cancelLocked(order, CANCELLED_BY_CUSTOMER);
        log.info("Order {} cancelled by its owner", order.getOrderNumber());
        return order;
    }

    /**
     * Expiry for one order (called per tenant by the cleanup job). Returns true when an unpaid order was cancelled.
     * A COMPLETED order is never touched; an already FAILED/CANCELLED order only has straggling reservations released.
     */
    @Transactional
    public boolean expire(String orderNumber, String reason) {
        Long id = orderRepository.findIdByOrderNumber(orderNumber).orElse(null);
        if (id == null) {
            log.warn("Skipping cleanup for unknown order {}", orderNumber);
            return false;
        }
        Order order = orderRepository.lockById(id).orElse(null);
        if (order == null) {
            return false;
        }
        PaymentStatus status = order.getStatus();
        if (status == PaymentStatus.COMPLETED) {
            log.debug("Skipping cleanup for order {} because it is already COMPLETED", orderNumber);
            return false;
        }
        if (status == PaymentStatus.CANCELLED || status == PaymentStatus.FAILED) {
            releaseIfReserved(order.getOrderNumber());
            return false;
        }
        cancelLocked(order, reason);
        return true;
    }

    /** Caller holds the order row lock and has verified the order is PENDING/INITIATED. */
    private void cancelLocked(Order order, String reason) {
        inventoryManagementService.releaseOrderInventory(order.getOrderNumber());
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        orderRepository.save(order);

        paymentRepository.findByOrder_OrderNumber(order.getOrderNumber()).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.COMPLETED && payment.getStatus() != PaymentStatus.CANCELLED) {
                payment.setStatus(PaymentStatus.CANCELLED);
                payment.setFailureReason(reason);
                paymentRepository.save(payment);
            }
        });
    }

    private void releaseIfReserved(String orderNumber) {
        if (inventoryAllocationRepository.existsByReferenceTypeAndReferenceIdAndStatus(
                InventoryReferenceType.ORDER, orderNumber, InventoryAllocationStatus.RESERVED)) {
            inventoryManagementService.releaseOrderInventory(orderNumber);
            log.info("Released straggling reservation of closed order {}", orderNumber);
        }
    }
}

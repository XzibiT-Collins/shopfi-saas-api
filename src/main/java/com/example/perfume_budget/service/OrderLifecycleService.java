package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.enums.CancelSource;
import com.example.perfume_budget.enums.OrderAuditEventType;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderAuditEvent;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.OrderAuditEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import java.time.LocalDateTime;
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
    static final String CANCELLED_BY_MERCHANT = "Cancelled by the merchant";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryManagementService inventoryManagementService;
    private final InventoryAllocationRepository inventoryAllocationRepository;
    private final OrderAuditEventRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CouponRedemptionService couponRedemptionService;

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
        cancelLocked(order, CANCELLED_BY_CUSTOMER, CancelSource.CUSTOMER, null);
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
            releaseCouponIfHeld(order); // straggler: closed without giving its coupon redemption back
            return false;
        }
        cancelLocked(order, reason, CancelSource.EXPIRY, null);
        return true;
    }

    /** Caller holds the order row lock and has verified the order is PENDING/INITIATED. */
    private void cancelLocked(Order order, String reason, CancelSource source, String actorEmail) {
        inventoryManagementService.releaseOrderInventory(order.getOrderNumber());
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        markCancelled(order, source, actorEmail);
        orderRepository.save(order);

        paymentRepository.findByOrder_OrderNumber(order.getOrderNumber()).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.COMPLETED && payment.getStatus() != PaymentStatus.CANCELLED) {
                payment.setStatus(PaymentStatus.CANCELLED);
                payment.setFailureReason(reason);
                paymentRepository.save(payment);
            }
        });
        // API-04: the order's coupon redemption goes back exactly once (we hold the order row lock). Lock order
        // order -> stock -> coupon: the coupon UPDATE is deferred to commit, after the stock release above.
        couponRedemptionService.releaseForOrder(order);
    }

    private static void markCancelled(Order order, CancelSource source, String actorEmail) {
        order.setCancelledAt(LocalDateTime.now());
        order.setCancelSource(source);
        order.setCancelledBy(actorEmail);
    }

    // ------------------------------------------------------------------------------------------------ admin (ORD-01)

    /** Result of an admin status change: the order after the change and the payment that goes with it (may be null). */
    public record AdminUpdate(Order order, Payment payment) {}

    /**
     * Admin delivery-status change. Locks the order row first and decides from what it reads AFTER the lock, so two
     * simultaneous cancels (or a cancel racing a payment webhook) serialise and each effect happens exactly once.
     *
     * <ol>
     *   <li>same status as now: no-op (also for final states and regardless of {@code expectedVersion}, so a retry of a
     *       request that already succeeded is a 200, not a conflict);</li>
     *   <li>{@code expectedVersion} given and different from the order's version: 409 ORDER_VERSION_CONFLICT;</li>
     *   <li>from a final state: 400 ORDER_FINAL_STATE; not in {@link OrderTransitionRules}: 400 ORDER_TRANSITION_NOT_ALLOWED;</li>
     *   <li>DELIVERED while the payment is not COMPLETED: 400 ORDER_NOT_PAID;</li>
     *   <li>CANCELLED goes through {@link #cancelByAdminLocked}.</li>
     * </ol>
     */
    @Transactional
    public AdminUpdate adminUpdateStatus(String orderNumber, OrderProcessingStatus target, Long expectedVersion,
                                         String actorEmail) {
        Order order = lockByNumber(orderNumber);
        Payment payment = paymentRepository.findByOrder_OrderNumber(order.getOrderNumber()).orElse(null);
        OrderProcessingStatus from = order.getDeliveryStatus();

        if (from == target) {
            return new AdminUpdate(order, payment);
        }
        if (expectedVersion != null && !expectedVersion.equals(order.getVersion())) {
            throw new ApiCodeException(HttpStatus.CONFLICT, "ORDER_VERSION_CONFLICT",
                    "The order was changed by someone else. Reload it and try again.",
                    Map.of("orderNumber", order.getOrderNumber(), "currentVersion", order.getVersion(),
                            "deliveryStatus", String.valueOf(from)));
        }
        if (from == OrderProcessingStatus.DELIVERED) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "ORDER_FINAL_STATE",
                    "Order is already delivered and can not be updated.");
        }
        if (from == OrderProcessingStatus.CANCELLED) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "ORDER_FINAL_STATE",
                    "Order is already cancelled and can not be updated.");
        }
        if (!OrderTransitionRules.isAllowed(from, target)) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "ORDER_TRANSITION_NOT_ALLOWED",
                    "Order can not move from " + from + " to " + target + ".",
                    Map.of("from", String.valueOf(from), "to", String.valueOf(target)));
        }
        if (target == OrderProcessingStatus.DELIVERED && order.getStatus() != PaymentStatus.COMPLETED) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "ORDER_NOT_PAID",
                    "An order that has not been paid can not be marked delivered.",
                    Map.of("orderNumber", order.getOrderNumber(), "paymentStatus", String.valueOf(order.getStatus())));
        }

        boolean refundRequired = false;
        if (target == OrderProcessingStatus.CANCELLED) {
            refundRequired = cancelByAdminLocked(order, payment, from, actorEmail);
        } else {
            order.setDeliveryStatus(target);
        }
        Order saved = orderRepository.saveAndFlush(order); // flush: the response carries the bumped version
        auditRepository.save(OrderAuditEvent.builder()
                .orderId(saved.getId())
                .eventType(target == OrderProcessingStatus.CANCELLED ? OrderAuditEventType.CANCELLED : OrderAuditEventType.STATUS_CHANGED)
                .fromStatus(from)
                .toStatus(target)
                .refundRequired(refundRequired)
                .actorEmail(actorEmail)
                .createdAt(LocalDateTime.now())
                .build());
        // Last statement: the customer e-mail is only queued once nothing else can fail (listener runs after commit).
        eventPublisher.publishEvent(OrderStatusChangeEvent.builder()
                .orderNumber(saved.getOrderNumber())
                .customerEmail(saved.getUser().getEmail())
                .status(target)
                .build());
        return new AdminUpdate(saved, payment);
    }

    /**
     * Real admin cancel (caller holds the row lock, the transition was validated).
     * <ul>
     *   <li>unpaid (PENDING/INITIATED): release the reservation, cancel the open payment, order -> CANCELLED;</li>
     *   <li>payment already FAILED/CANCELLED: release any straggling reservation, delivery -> CANCELLED;</li>
     *   <li>paid (COMPLETED): delivery -> CANCELLED, payment.refundRequired = true, and the consumed stock goes back
     *       to the shelf only when nothing left the shop yet (from PENDING/PACKING; OUT_FOR_DELIVERY goods are with
     *       the courier and are restocked by hand on return).</li>
     * </ul>
     * @return true when a manual refund is now required
     */
    private boolean cancelByAdminLocked(Order order, Payment payment, OrderProcessingStatus from, String actorEmail) {
        PaymentStatus status = order.getStatus();
        if (status == PaymentStatus.PENDING || status == PaymentStatus.INITIATED) {
            cancelLocked(order, CANCELLED_BY_MERCHANT, CancelSource.ADMIN, actorEmail);
            return false;
        }
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        markCancelled(order, CancelSource.ADMIN, actorEmail);
        if (status != PaymentStatus.COMPLETED) {
            releaseIfReserved(order.getOrderNumber());
            releaseCouponIfHeld(order);
            return false;
        }
        if (from == OrderProcessingStatus.PENDING || from == OrderProcessingStatus.PACKING) {
            int units = inventoryManagementService.restockConsumedOrderInventory(order);
            log.info("Admin cancel of paid order {}: {} unit(s) returned to stock", order.getOrderNumber(), units);
        }
        if (payment != null) {
            payment.setRefundRequired(true);
            paymentRepository.save(payment);
        }
        return true;
    }

    /**
     * Marks that the shop refunded a refund-required order by hand. 409 REFUND_NOT_REQUIRED when there is nothing to
     * acknowledge (also for the second of two simultaneous or repeated calls); exactly one audit row per refund.
     */
    @Transactional
    public AdminUpdate acknowledgeRefund(String orderNumber, String actorEmail) {
        Order order = lockByNumber(orderNumber);
        Payment payment = paymentRepository.findByOrder_OrderNumber(order.getOrderNumber()).orElse(null);
        if (payment == null || !payment.isRefundRequired()) {
            throw new ApiCodeException(HttpStatus.CONFLICT, "REFUND_NOT_REQUIRED",
                    "No refund is required for this order.", Map.of("orderNumber", order.getOrderNumber()));
        }
        LocalDateTime now = LocalDateTime.now();
        payment.setRefundRequired(false);
        payment.setRefundAcknowledgedAt(now);
        payment.setRefundAcknowledgedBy(actorEmail);
        paymentRepository.save(payment);
        order.setUpdatedAt(now); // dirties the row so the version moves: the order's refund state changed
        Order saved = orderRepository.saveAndFlush(order);
        auditRepository.save(OrderAuditEvent.builder()
                .orderId(saved.getId())
                .eventType(OrderAuditEventType.REFUND_ACKNOWLEDGED)
                .refundRequired(false)
                .actorEmail(actorEmail)
                .createdAt(now)
                .build());
        log.info("Refund acknowledged for order {}", saved.getOrderNumber());
        return new AdminUpdate(saved, payment);
    }

    private Order lockByNumber(String orderNumber) {
        String clean = orderNumber == null ? "" : orderNumber.trim().toUpperCase();
        Long id = orderRepository.findIdByOrderNumber(clean)
                .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));
        return orderRepository.lockById(id)
                .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ORDER_NOT_FOUND));
    }

    /** Straggler release; the coupon UPDATE itself runs at commit, after every stock write (coupon row LAST). */
    private void releaseCouponIfHeld(Order order) {
        if (order.getCoupon() != null && !order.isCouponRedemptionReleased()) {
            couponRedemptionService.releaseForOrder(order);
            orderRepository.save(order);
        }
    }

    private void releaseIfReserved(String orderNumber) {
        if (inventoryAllocationRepository.existsByReferenceTypeAndReferenceIdAndStatus(
                InventoryReferenceType.ORDER, orderNumber, InventoryAllocationStatus.RESERVED)) {
            inventoryManagementService.releaseOrderInventory(orderNumber);
            log.info("Released straggling reservation of closed order {}", orderNumber);
        }
    }
}

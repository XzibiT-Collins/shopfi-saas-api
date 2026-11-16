package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.CancelSource;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The single place where a provider payment outcome is applied to an order. Called by the signature-verified webhook
 * and by the server-side provider verification; never with anything the browser supplied.
 *
 * <p>Every method is idempotent and concurrency safe: the order row is locked first and the payment/order status is
 * re-read after the lock, so a duplicate webhook, a webhook racing a verify call, or a late success racing a cancel
 * applies at most once and the stock reservation is consumed/released exactly once. Runs under the caller's tenant
 * (webhook: TaskDecorator-propagated context; verify: request context).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    public enum Outcome {
        COMPLETED,
        /** Paid after the order was cancelled/failed and stock could not be re-reserved: payment kept, refund flagged. */
        COMPLETED_REFUND_REQUIRED,
        FAILED,
        /** Nothing to do: already settled, closed, or the event does not change anything. */
        UNCHANGED,
        /** Amount/currency from the provider does not match what we asked it to charge: not applied. */
        MISMATCH,
        NOT_FOUND
    }

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final OrderService orderService;
    private final OrderStockReservationService reservationService;
    private final BookkeepingService bookkeepingService;
    private final ApplicationEventPublisher eventPublisher;
    private final InventoryManagementService inventoryManagementService;
    private final CouponRedemptionService couponRedemptionService;

    /**
     * @param providerAmountMinor amount the provider says it collected in minor units (pesewas), or null when the
     *                            event does not carry one
     * @param providerCurrency    currency the provider reports, or null
     */
    @Transactional
    public Outcome applySuccess(String providerReference, String channel, BigDecimal providerAmountMinor,
                                String providerCurrency, String gatewayResponseJson) {
        if (providerAmountMinor == null || providerCurrency == null || providerCurrency.isBlank()) {
            // Fail closed: money is only applied against provider-confirmed amount and currency.
            log.warn("Success without provider amount/currency not applied (reference {})", providerReference);
            return Outcome.MISMATCH;
        }
        Long orderId = paymentRepository.findOrderIdByProviderReference(providerReference).orElse(null);
        if (orderId == null) {
            return Outcome.NOT_FOUND;
        }
        Order order = orderRepository.lockById(orderId).orElse(null);
        Payment payment = paymentRepository.findByProviderPaymentReference(providerReference);
        if (order == null || payment == null) {
            return Outcome.NOT_FOUND;
        }
        if (payment.getStatus() == PaymentStatus.COMPLETED || order.getStatus() == PaymentStatus.COMPLETED) {
            return Outcome.UNCHANGED;
        }
        if (!amountMatches(payment, providerAmountMinor, providerCurrency)) {
            log.warn("Provider amount/currency does not match order {}; payment not applied", order.getOrderNumber());
            return Outcome.MISMATCH;
        }

        payment.setGatewayResponse(gatewayResponseJson);

        // deliveryStatus CANCELLED is closed too (ORD-01): before the real admin cancel, an admin "cancel" only set the
        // delivery status and left the order payable.
        boolean wasClosed = order.getStatus() == PaymentStatus.CANCELLED || order.getStatus() == PaymentStatus.FAILED
                || payment.getStatus() == PaymentStatus.CANCELLED || payment.getStatus() == PaymentStatus.FAILED
                || order.getDeliveryStatus() == OrderProcessingStatus.CANCELLED;
        // A merchant cancel is a decision: a late payment is NEVER used to revive such an order (customer cancel and
        // expiry keep the PAY-01 behaviour). Orders cancelled by the old delivery-status-only "cancel" (payment still
        // open, no cancel source) are treated the same way.
        boolean cancelledByMerchant = order.getCancelSource() == CancelSource.ADMIN
                || (order.getCancelSource() == null && order.getDeliveryStatus() == OrderProcessingStatus.CANCELLED
                && order.getStatus() != PaymentStatus.CANCELLED && order.getStatus() != PaymentStatus.FAILED);
        if (wasClosed && cancelledByMerchant) {
            inventoryManagementService.releaseOrderInventory(order.getOrderNumber()); // only RESERVED rows: idempotent
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setPaymentMethod(channel);
            payment.setPaidAt(LocalDateTime.now());
            payment.setRefundRequired(true);
            payment.setFailureReason("Paid after the merchant cancelled the order: refund required");
            paymentRepository.save(payment);
            if (order.getStatus() != PaymentStatus.CANCELLED) {
                order.setStatus(PaymentStatus.CANCELLED);
            }
            couponRedemptionService.releaseForOrder(order); // stays cancelled (idempotent); UPDATE runs at commit, last
            // Always dirty the row so the version moves: an admin holding the old version sees refundRequired changed.
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);
            log.warn("Payment for merchant-cancelled order {} flagged for refund", order.getOrderNumber());
            return Outcome.COMPLETED_REFUND_REQUIRED;
        }
        if (wasClosed) {
            // The customer really paid. Take the goods back out of stock if we still can.
            boolean reserved;
            try {
                reserved = reservationService.reserveForLatePayment(order.getOrderNumber(), order.getItems());
            } catch (BadRequestException e) {
                // ONLY "Insufficient stock" is a business outcome. Any other failure (pool/lock timeout, transient DB
                // error) propagates: this transaction rolls back, the payment stays unsettled and the next webhook or
                // verify poll retries instead of wrongly flagging a refund.
                reserved = false;
            }
            if (!reserved) {
                payment.setStatus(PaymentStatus.COMPLETED);
                payment.setPaymentMethod(channel);
                payment.setPaidAt(LocalDateTime.now());
                payment.setRefundRequired(true);
                payment.setFailureReason("Paid after the order was closed and stock was unavailable: refund required");
                paymentRepository.save(payment);
                log.warn("Late payment for closed order {} could not be fulfilled; flagged for refund", order.getOrderNumber());
                return Outcome.COMPLETED_REFUND_REQUIRED;
            }
            if (order.getDeliveryStatus() == OrderProcessingStatus.CANCELLED) {
                order.setDeliveryStatus(OrderProcessingStatus.PENDING);
            }
            // The order is live again: it must not keep reporting that it was cancelled.
            order.setCancelledAt(null);
            order.setCancelSource(null);
            order.setCancelledBy(null);
            payment.setFailureReason(null);
            log.info("Late payment re-activated closed order {}", order.getOrderNumber());
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaymentMethod(channel);
        payment.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);

        order.setPaymentReference(saved.getSystemReference() != null
                ? saved.getSystemReference()
                : saved.getProviderPaymentReference());
        order.setStatus(PaymentStatus.COMPLETED);
        orderRepository.save(order);

        cartRepository.deleteAllByUserEmail(payment.getCustomerEmail());
        // API-05 rework: the stock finalisation takes the order's product rows first (ProductStockLocks, ascending id)
        // and only THEN the sold counts are bumped. The other way round the per-item UPDATEs locked the products in
        // order-item order, so a webhook for [high id, low id] and a checkout of the same products (low -> high)
        // deadlocked. Not locked up front here: the late-payment re-reservation above runs in its own transaction
        // (REQUIRES_NEW) and would wait for this one.
        orderService.finalizeReservedStock(order);
        order.getItems().forEach(item -> productRepository.incrementSoldCount(item.getProductId(), item.getQuantity()));
        bookkeepingService.recordSale(order, saved);
        // API-04: a revived order holds its coupon redemption again (released when it was closed). Coupon row LAST:
        // after the stock re-reservation, sold counts, stock finalisation and ledger (the UPDATE itself runs at commit)
        couponRedemptionService.reacquireForOrder(order);
        // Last: receipt / admin e-mail / WebSocket notification. The listener runs AFTER COMMIT (rework 3): a retried
        // attempt that rolls back (deadlock, COUPON_BUSY from the deferred coupon UPDATE at commit) sends nothing.
        eventPublisher.publishEvent(saved);
        log.info("Payment completed for order {}", order.getOrderNumber());
        return Outcome.COMPLETED;
    }

    /** A terminal provider failure (charge.failed). Never overrides a COMPLETED payment. */
    @Transactional
    public Outcome applyFailure(String providerReference, String channel, String message, String gatewayResponseJson) {
        Long orderId = paymentRepository.findOrderIdByProviderReference(providerReference).orElse(null);
        if (orderId == null) {
            return Outcome.NOT_FOUND;
        }
        Order order = orderRepository.lockById(orderId).orElse(null);
        Payment payment = paymentRepository.findByProviderPaymentReference(providerReference);
        if (order == null || payment == null) {
            return Outcome.NOT_FOUND;
        }
        PaymentStatus current = payment.getStatus();
        if (current == PaymentStatus.COMPLETED || current == PaymentStatus.FAILED || current == PaymentStatus.CANCELLED
                || order.getStatus() == PaymentStatus.COMPLETED) {
            return Outcome.UNCHANGED;
        }
        payment.setGatewayResponse(gatewayResponseJson);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setPaymentMethod(channel);
        payment.setFailureReason(message);
        Payment saved = paymentRepository.save(payment);

        order.setPaymentReference(saved.getSystemReference() != null
                ? saved.getSystemReference()
                : saved.getProviderPaymentReference());
        order.setStatus(PaymentStatus.FAILED);
        orderRepository.save(order);
        orderService.releaseStock(order.getOrderNumber());
        // API-04: exactly once, under the order lock. Lock order order -> stock -> coupon: the coupon UPDATE runs at
        // commit, after the stock release above (a checkout may hold those stock rows across its Paystack call)
        couponRedemptionService.releaseForOrder(order);
        log.info("Payment failed for order {}", order.getOrderNumber());
        return Outcome.FAILED;
    }

    private static boolean amountMatches(Payment payment, BigDecimal providerAmountMinor, String providerCurrency) {
        if (payment.getAmount() == null || payment.getAmount().movePointRight(2).compareTo(providerAmountMinor) != 0) {
            return false;
        }
        return payment.getCurrency() != null && payment.getCurrency().name().equalsIgnoreCase(providerCurrency);
    }
}

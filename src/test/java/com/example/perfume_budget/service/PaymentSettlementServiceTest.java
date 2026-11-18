package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.PaymentSettlementService.Outcome;
import com.example.perfume_budget.service.interfaces.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock CartRepository cartRepository;
    @Mock OrderService orderService;
    @Mock OrderStockReservationService reservationService;
    @Mock BookkeepingService bookkeepingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.example.perfume_budget.service.interfaces.InventoryManagementService inventoryManagementService;
    @Mock CouponRedemptionService couponRedemptionService;
    @InjectMocks PaymentSettlementService service;

    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        order = Order.builder().id(1L).orderNumber("ORD-1").status(PaymentStatus.INITIATED)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .items(new ArrayList<>(java.util.List.of(OrderItem.builder().productId(5L).quantity(2).build()))).build();
        payment = Payment.builder().id(2L).order(order).providerPaymentReference("REF123456").systemReference("SYS")
                .status(PaymentStatus.INITIATED).customerEmail("c@example.com")
                .amount(new BigDecimal("12.50")).currency(CurrencyCode.GHS).build();
        lenient().when(paymentRepository.findOrderIdByProviderReference("REF123456")).thenReturn(Optional.of(1L));
        lenient().when(orderRepository.lockById(1L)).thenReturn(Optional.of(order));
        lenient().when(paymentRepository.findByProviderPaymentReference("REF123456")).thenReturn(payment);
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void successCompletesOnceAndSecondCallIsUnchanged() {
        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.COMPLETED, order.getStatus());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(Outcome.UNCHANGED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        verify(cartRepository, times(1)).deleteAllByUserEmail("c@example.com");
        verify(eventPublisher, times(1)).publishEvent(payment);
        verify(orderService, times(1)).finalizeReservedStock(order);
        verify(bookkeepingService, times(1)).recordSale(order, payment);
        verify(productRepository, times(1)).incrementSoldCount(5L, 2);
    }

    @Test
    void soldCountsAreBumpedOnlyAfterTheStockFinalisationLockedTheProducts() {
        // API-05 rework: incrementSoldCount is an UPDATE of the product row. Before the finalisation took the product
        // locks (ascending id) it locked the rows in order-item order -> deadlock with a checkout of the same products.
        order.getItems().add(0, OrderItem.builder().productId(9L).quantity(1).build()); // items: [9, 5]
        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        org.mockito.InOrder inOrder = inOrder(orderService, productRepository);
        inOrder.verify(orderService).finalizeReservedStock(order);
        inOrder.verify(productRepository).incrementSoldCount(9L, 1);
        inOrder.verify(productRepository).incrementSoldCount(5L, 2);
    }

    @Test
    void amountOrCurrencyMismatchIsNotApplied() {
        assertEquals(Outcome.MISMATCH, service.applySuccess("REF123456", "card", new BigDecimal("100"), "GHS", "{}"));
        assertEquals(Outcome.MISMATCH, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "USD", "{}"));
        assertEquals(PaymentStatus.INITIATED, order.getStatus());
        verifyNoInteractions(orderService, bookkeepingService);
    }

    @Test
    void unknownReferenceIsNotFound() {
        when(paymentRepository.findOrderIdByProviderReference("NOPE")).thenReturn(Optional.empty());
        assertEquals(Outcome.NOT_FOUND, service.applySuccess("NOPE", null, new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(Outcome.NOT_FOUND, service.applyFailure("NOPE", null, null, "{}"));
    }

    @Test
    void lateSuccessCompletesFailedOrderWhenStockCanBeReReserved() {
        order.setStatus(PaymentStatus.FAILED);
        payment.setStatus(PaymentStatus.FAILED);
        when(reservationService.reserveForLatePayment("ORD-1", order.getItems())).thenReturn(true);

        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.COMPLETED, order.getStatus());
        verify(orderService).finalizeReservedStock(order);
        verify(couponRedemptionService).reacquireForOrder(order); // revived: holds its coupon redemption again
    }

    @Test
    void lateSuccessOnCancelledOrderReactivatesDeliveryStatus() {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(reservationService.reserveForLatePayment(any(), any())).thenReturn(true);

        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(OrderProcessingStatus.PENDING, order.getDeliveryStatus());
    }

    @Test
    void lateSuccessWithoutStockKeepsOrderClosedAndFlagsRefund() {
        order.setStatus(PaymentStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(reservationService.reserveForLatePayment(any(), any()))
                .thenThrow(new com.example.perfume_budget.exception.BadRequestException("Insufficient stock for: X"));

        assertEquals(Outcome.COMPLETED_REFUND_REQUIRED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertTrue(payment.isRefundRequired());
        verifyNoInteractions(orderService);
        // ORD-02b: the cash is booked against Refunds Payable (no sale), once, with the saved payment
        verify(bookkeepingService, times(1)).recordLatePaymentReceived(order, payment);
        verifyNoMoreInteractions(bookkeepingService);
    }

    @Test
    void lateSuccessOnMerchantCancelledOrderIsRefundRequiredAndNeverRevived() {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        order.setCancelSource(com.example.perfume_budget.enums.CancelSource.ADMIN);
        payment.setStatus(PaymentStatus.CANCELLED);

        assertEquals(Outcome.COMPLETED_REFUND_REQUIRED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertTrue(payment.isRefundRequired());
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
        assertEquals(OrderProcessingStatus.CANCELLED, order.getDeliveryStatus());
        verifyNoInteractions(reservationService, orderService);
        verify(inventoryManagementService).releaseOrderInventory("ORD-1");
        verify(cartRepository, never()).deleteAllByUserEmail(any());
        // ORD-02b: booked as cash in + refund owed after the payment is COMPLETED and the stock released
        org.mockito.InOrder io = inOrder(inventoryManagementService, paymentRepository, bookkeepingService, couponRedemptionService);
        io.verify(inventoryManagementService).releaseOrderInventory("ORD-1");
        io.verify(paymentRepository).save(payment);
        io.verify(bookkeepingService).recordLatePaymentReceived(order, payment);
        io.verify(couponRedemptionService).releaseForOrder(order);
        verifyNoMoreInteractions(bookkeepingService);
    }

    @Test
    void deliveryStatusCancelledAloneCountsAsClosedForAnOpenPayment() {
        // the pre-ORD-01 admin "cancel": delivery CANCELLED, order/payment still INITIATED and payable
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);

        assertEquals(Outcome.COMPLETED_REFUND_REQUIRED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertTrue(payment.isRefundRequired());
        assertEquals(PaymentStatus.CANCELLED, order.getStatus(), "normalised to the refund-required state");
        assertEquals(OrderProcessingStatus.CANCELLED, order.getDeliveryStatus());
        verifyNoInteractions(orderService);
        verify(inventoryManagementService).releaseOrderInventory("ORD-1");
        verify(bookkeepingService).recordLatePaymentReceived(order, payment);
        verifyNoMoreInteractions(bookkeepingService);
    }

    @Test
    void repeatedLateSuccessForMerchantCancelledOrderIsUnchanged() {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        order.setCancelSource(com.example.perfume_budget.enums.CancelSource.ADMIN);
        payment.setStatus(PaymentStatus.CANCELLED);
        service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}");
        assertEquals(Outcome.UNCHANGED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        verify(inventoryManagementService, times(1)).releaseOrderInventory("ORD-1");
        verify(bookkeepingService, times(1)).recordLatePaymentReceived(order, payment); // redelivery books nothing more
    }

    @Test
    void repeatedLateSuccessWithoutStockBooksOnce() {
        order.setStatus(PaymentStatus.FAILED);
        payment.setStatus(PaymentStatus.FAILED);
        when(reservationService.reserveForLatePayment(any(), any())).thenReturn(false);
        assertEquals(Outcome.COMPLETED_REFUND_REQUIRED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(Outcome.UNCHANGED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        verify(bookkeepingService, times(1)).recordLatePaymentReceived(order, payment);
        verify(bookkeepingService, never()).recordSale(any(), any());
    }

    @Test
    void anOrdinaryOrRevivedPaymentIsNeverBookedAsALatePayment() {
        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        order.setStatus(PaymentStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(reservationService.reserveForLatePayment(any(), any())).thenReturn(true);
        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        verify(bookkeepingService, times(2)).recordSale(order, payment);
        verify(bookkeepingService, never()).recordLatePaymentReceived(any(), any());
    }

    @Test
    void revivedOrderNoLongerReportsItWasCancelled() {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        order.setCancelSource(com.example.perfume_budget.enums.CancelSource.CUSTOMER);
        order.setCancelledAt(java.time.LocalDateTime.now());
        order.setCancelledBy("someone@shop.test");
        payment.setStatus(PaymentStatus.CANCELLED);
        when(reservationService.reserveForLatePayment(any(), any())).thenReturn(true);

        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(OrderProcessingStatus.PENDING, order.getDeliveryStatus());
        assertNull(order.getCancelledAt());
        assertNull(order.getCancelSource());
        assertNull(order.getCancelledBy());
    }

    @Test
    void latePaymentOnMerchantCancelledOrderDirtiesTheOrderSoItsVersionMoves() {
        order.setStatus(PaymentStatus.CANCELLED);
        order.setDeliveryStatus(OrderProcessingStatus.CANCELLED);
        order.setCancelSource(com.example.perfume_budget.enums.CancelSource.ADMIN);
        payment.setStatus(PaymentStatus.CANCELLED);
        assertNull(order.getUpdatedAt());
        service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}");
        assertNotNull(order.getUpdatedAt());
        verify(orderRepository).save(order);
    }

    @Test
    void failureMarksFailedAndReleasesStockOnce() {
        assertEquals(Outcome.FAILED, service.applyFailure("REF123456", "card", "Declined", "{}"));
        assertEquals(PaymentStatus.FAILED, order.getStatus());
        assertEquals("Declined", payment.getFailureReason());
        assertEquals(Outcome.UNCHANGED, service.applyFailure("REF123456", "card", "Declined", "{}"));
        verify(orderService, times(1)).releaseStock("ORD-1");
        verify(couponRedemptionService, times(1)).releaseForOrder(order);
    }

    @Test
    void failureNeverOverridesCompleted() {
        payment.setStatus(PaymentStatus.COMPLETED);
        order.setStatus(PaymentStatus.COMPLETED);
        assertEquals(Outcome.UNCHANGED, service.applyFailure("REF123456", "card", "x", "{}"));
        assertEquals(PaymentStatus.COMPLETED, order.getStatus());
        verifyNoInteractions(orderService);
    }

    @Test
    void transientReserveFailurePropagatesAndLeavesPaymentUnsettled_thenRetryCompletes() {
        order.setStatus(PaymentStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(reservationService.reserveForLatePayment(any(), any()))
                .thenThrow(new IllegalStateException("Hikari timeout"))
                .thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertFalse(payment.isRefundRequired());
        verify(paymentRepository, never()).save(any(Payment.class));

        assertEquals(Outcome.COMPLETED, service.applySuccess("REF123456", "card", new BigDecimal("1250"), "GHS", "{}"));
        assertEquals(PaymentStatus.COMPLETED, order.getStatus());
    }

    @Test
    void successWithoutProviderAmountOrCurrencyFailsClosed() {
        assertEquals(Outcome.MISMATCH, service.applySuccess("REF123456", "card", null, "GHS", "{}"));
        assertEquals(Outcome.MISMATCH, service.applySuccess("REF123456", "card", new BigDecimal("1250"), null, "{}"));
        assertEquals(Outcome.MISMATCH, service.applySuccess("REF123456", "card", new BigDecimal("1250"), " ", "{}"));
        assertEquals(PaymentStatus.INITIATED, order.getStatus());
        verifyNoInteractions(orderService, bookkeepingService, cartRepository, eventPublisher);
    }

    @Test
    void failureNeverFlipsRefundRequiredState() {
        order.setStatus(PaymentStatus.CANCELLED);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setRefundRequired(true);
        assertEquals(Outcome.UNCHANGED, service.applyFailure("REF123456", "card", "x", "{}"));
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertTrue(payment.isRefundRequired());
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
        verifyNoInteractions(orderService);
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}

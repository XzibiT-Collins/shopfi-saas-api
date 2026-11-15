package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock InventoryManagementService inventory;
    @Mock InventoryAllocationRepository allocations;
    @Mock com.example.perfume_budget.repository.OrderAuditEventRepository audits;
    @Mock org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks OrderLifecycleService service;

    private final User owner = User.builder().id(1L).build();
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        order = Order.builder().id(10L).orderNumber("ORD-1").status(PaymentStatus.INITIATED)
                .deliveryStatus(OrderProcessingStatus.PENDING).build();
        payment = Payment.builder().id(20L).order(order).status(PaymentStatus.INITIATED).build();
        lenient().when(orderRepository.findIdByOrderNumberAndUser("ORD-1", owner)).thenReturn(Optional.of(10L));
        lenient().when(orderRepository.findIdByOrderNumber("ORD-1")).thenReturn(Optional.of(10L));
        lenient().when(orderRepository.lockById(10L)).thenReturn(Optional.of(order));
        lenient().when(paymentRepository.findByOrder_OrderNumber("ORD-1")).thenReturn(Optional.of(payment));
        lenient().when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        order.setUser(User.builder().id(1L).email("cust@shop.test").build());
        order.setVersion(5L);
    }

    @Test
    void ownerCancelReleasesStockOnceAndCancelsOrderAndPayment() {
        Order result = service.cancelByOwner(" ord-1 ", owner);
        assertEquals(PaymentStatus.CANCELLED, result.getStatus());
        assertEquals(OrderProcessingStatus.CANCELLED, result.getDeliveryStatus());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(inventory, times(1)).releaseOrderInventory("ORD-1");
    }

    @Test
    void secondCancelIsIdempotentAndDoesNotReleaseAgain() {
        service.cancelByOwner("ORD-1", owner);
        service.cancelByOwner("ORD-1", owner);
        verify(inventory, times(1)).releaseOrderInventory("ORD-1");
    }

    @Test
    void notOwnedOrUnknownIs404WithSameCode() {
        when(orderRepository.findIdByOrderNumberAndUser("ORD-9", owner)).thenReturn(Optional.empty());
        ApiCodeException e = assertThrows(ApiCodeException.class, () -> service.cancelByOwner("ORD-9", owner));
        assertEquals(404, e.getStatus().value());
        assertEquals("ORDER_NOT_FOUND", e.getCode());
        verifyNoInteractions(inventory);
    }

    @Test
    void paidOrFailedOrderCannotBeCancelled() {
        for (PaymentStatus s : new PaymentStatus[]{PaymentStatus.COMPLETED, PaymentStatus.FAILED}) {
            order.setStatus(s);
            ApiCodeException e = assertThrows(ApiCodeException.class, () -> service.cancelByOwner("ORD-1", owner));
            assertEquals("ORDER_NOT_CANCELLABLE", e.getCode());
        }
        verifyNoInteractions(inventory);
    }

    @Test
    void expireCancelsUnpaidOrderWithReason() {
        assertTrue(service.expire("ORD-1", "Reservation expired after 1 hour"));
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
        assertEquals("Reservation expired after 1 hour", payment.getFailureReason());
        verify(inventory).releaseOrderInventory("ORD-1");
    }

    @Test
    void expireNeverTouchesCompletedOrder() {
        order.setStatus(PaymentStatus.COMPLETED);
        assertFalse(service.expire("ORD-1", "x"));
        verifyNoInteractions(inventory);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void expireOfClosedOrderOnlyReleasesStragglersOnce() {
        order.setStatus(PaymentStatus.CANCELLED);
        when(allocations.existsByReferenceTypeAndReferenceIdAndStatus(any(), any(), any())).thenReturn(true, false);
        assertFalse(service.expire("ORD-1", "x"));
        assertFalse(service.expire("ORD-1", "x"));
        verify(inventory, times(1)).releaseOrderInventory("ORD-1");
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
    }

    // ---------------------------------------------------------------------------------------- admin (ORD-01)

    private OrderLifecycleService.AdminUpdate admin(OrderProcessingStatus to, Long version) {
        return service.adminUpdateStatus("ord-1", to, version, "boss@shop.test");
    }

    private ApiCodeException adminFails(OrderProcessingStatus to, Long version) {
        return assertThrows(ApiCodeException.class, () -> admin(to, version));
    }

    @Test
    void adminCancelUnpaidReleasesOnceCancelsPaymentAndAudits() {
        admin(OrderProcessingStatus.CANCELLED, null);
        assertEquals(PaymentStatus.CANCELLED, order.getStatus());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertEquals(CancelSource.ADMIN, order.getCancelSource());
        assertEquals("boss@shop.test", order.getCancelledBy());
        assertNotNull(order.getCancelledAt());
        assertFalse(payment.isRefundRequired());
        verify(inventory, times(1)).releaseOrderInventory("ORD-1");
        verify(audits).save(argThat(e -> e.getEventType() == OrderAuditEventType.CANCELLED && !e.isRefundRequired()
                && "boss@shop.test".equals(e.getActorEmail())));
        verify(events).publishEvent(any(com.example.perfume_budget.events.OrderStatusChangeEvent.class));
        // retry is a no-op
        admin(OrderProcessingStatus.CANCELLED, 1L);
        verify(inventory, times(1)).releaseOrderInventory("ORD-1");
        verify(audits, times(1)).save(any());
    }

    @Test
    void adminCancelPaidRestocksFromPackingAndFlagsRefund() {
        order.setStatus(PaymentStatus.COMPLETED);
        order.setDeliveryStatus(OrderProcessingStatus.PACKING);
        payment.setStatus(PaymentStatus.COMPLETED);
        admin(OrderProcessingStatus.CANCELLED, null);
        assertTrue(payment.isRefundRequired());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(PaymentStatus.COMPLETED, order.getStatus());
        verify(inventory).restockConsumedOrderInventory(order);
        verify(inventory, never()).releaseOrderInventory(any());
        verify(audits).save(argThat(e -> e.isRefundRequired()));
    }

    @Test
    void adminCancelPaidOutForDeliveryDoesNotRestock() {
        order.setStatus(PaymentStatus.COMPLETED);
        order.setDeliveryStatus(OrderProcessingStatus.OUT_FOR_DELIVERY);
        payment.setStatus(PaymentStatus.COMPLETED);
        admin(OrderProcessingStatus.CANCELLED, null);
        assertTrue(payment.isRefundRequired());
        verify(inventory, never()).restockConsumedOrderInventory(any());
    }

    @Test
    void adminCancelOfFailedOrderOnlyClosesDeliveryAndReleasesStragglers() {
        order.setStatus(PaymentStatus.FAILED);
        payment.setStatus(PaymentStatus.FAILED);
        when(allocations.existsByReferenceTypeAndReferenceIdAndStatus(InventoryReferenceType.ORDER, "ORD-1", InventoryAllocationStatus.RESERVED)).thenReturn(true);
        admin(OrderProcessingStatus.CANCELLED, null);
        assertEquals(PaymentStatus.FAILED, order.getStatus());
        assertEquals(OrderProcessingStatus.CANCELLED, order.getDeliveryStatus());
        assertFalse(payment.isRefundRequired());
        verify(inventory).releaseOrderInventory("ORD-1");
        verify(inventory, never()).restockConsumedOrderInventory(any());
    }

    @Test
    void transitionGraphIsEnforced() {
        order.setStatus(PaymentStatus.COMPLETED);
        order.setDeliveryStatus(OrderProcessingStatus.OUT_FOR_DELIVERY);
        assertEquals("ORDER_TRANSITION_NOT_ALLOWED", adminFails(OrderProcessingStatus.PACKING, null).getCode());
        assertEquals("ORDER_TRANSITION_NOT_ALLOWED", adminFails(OrderProcessingStatus.PENDING, null).getCode());
        admin(OrderProcessingStatus.DELIVERED, null);
        assertEquals("ORDER_FINAL_STATE", adminFails(OrderProcessingStatus.PACKING, null).getCode());
        assertEquals("ORDER_FINAL_STATE", adminFails(OrderProcessingStatus.CANCELLED, null).getCode());
        assertEquals(OrderProcessingStatus.DELIVERED, order.getDeliveryStatus());
    }

    @Test
    void unpaidCannotBeDeliveredForAnyNonCompletedPayment() {
        for (PaymentStatus s : new PaymentStatus[]{PaymentStatus.PENDING, PaymentStatus.INITIATED, PaymentStatus.FAILED, PaymentStatus.CANCELLED}) {
            order.setStatus(s);
            order.setDeliveryStatus(OrderProcessingStatus.PACKING);
            assertEquals("ORDER_NOT_PAID", adminFails(OrderProcessingStatus.DELIVERED, null).getCode(), s.name());
        }
        order.setStatus(PaymentStatus.COMPLETED);
        admin(OrderProcessingStatus.DELIVERED, null);
    }

    @Test
    void versionIsCheckedAfterTheNoOpShortcut() {
        ApiCodeException e = adminFails(OrderProcessingStatus.PACKING, 4L);
        assertEquals(409, e.getStatus().value());
        assertEquals("ORDER_VERSION_CONFLICT", e.getCode());
        assertEquals(OrderProcessingStatus.PENDING, order.getDeliveryStatus());
        admin(OrderProcessingStatus.PACKING, 5L);
        assertEquals(OrderProcessingStatus.PACKING, order.getDeliveryStatus());
        admin(OrderProcessingStatus.PACKING, 1L); // same status: 200 whatever the version
    }

    @Test
    void acknowledgeRefundClearsFlagOnceAndAudits() {
        payment.setRefundRequired(true);
        service.acknowledgeRefund("ord-1", "boss@shop.test");
        assertFalse(payment.isRefundRequired());
        assertEquals("boss@shop.test", payment.getRefundAcknowledgedBy());
        assertNotNull(payment.getRefundAcknowledgedAt());
        verify(audits).save(argThat(e -> e.getEventType() == OrderAuditEventType.REFUND_ACKNOWLEDGED && "boss@shop.test".equals(e.getActorEmail())));
        ApiCodeException e = assertThrows(ApiCodeException.class, () -> service.acknowledgeRefund("ORD-1", "boss@shop.test"));
        assertEquals("REFUND_NOT_REQUIRED", e.getCode());
        assertEquals(409, e.getStatus().value());
        verify(audits, times(1)).save(any());
    }

    @Test
    void ownerCancelAndExpiryRecordTheirSource() {
        service.cancelByOwner("ORD-1", owner);
        assertEquals(CancelSource.CUSTOMER, order.getCancelSource());
        assertNull(order.getCancelledBy());
        order.setStatus(PaymentStatus.INITIATED);
        order.setDeliveryStatus(OrderProcessingStatus.PENDING);
        service.expire("ORD-1", "expired");
        assertEquals(CancelSource.EXPIRY, order.getCancelSource());
    }

    @Test
    void aVersionNewerThanTheCurrentOneIsRejectedToo() {
        ApiCodeException e = adminFails(OrderProcessingStatus.PACKING, 6L);
        assertEquals(409, e.getStatus().value());
        assertEquals("ORDER_VERSION_CONFLICT", e.getCode());
        assertEquals(OrderProcessingStatus.PENDING, order.getDeliveryStatus());
    }

    @Test
    void adminCancelOfFailedOrDeadPaymentOrderRecordsAdminAsSource() {
        for (PaymentStatus s : new PaymentStatus[]{PaymentStatus.FAILED, PaymentStatus.CANCELLED}) {
            order.setStatus(s);
            order.setDeliveryStatus(OrderProcessingStatus.PENDING);
            order.setCancelSource(null);
            payment.setStatus(s);
            admin(OrderProcessingStatus.CANCELLED, null);
            assertEquals(CancelSource.ADMIN, order.getCancelSource(), s.name());
            assertEquals("boss@shop.test", order.getCancelledBy());
            assertNotNull(order.getCancelledAt());
        }
    }
}

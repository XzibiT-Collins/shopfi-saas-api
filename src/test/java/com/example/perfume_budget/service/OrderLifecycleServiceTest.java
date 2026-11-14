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
}

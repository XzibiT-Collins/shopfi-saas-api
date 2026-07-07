package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservedProductCleanupServiceTest {

    @Mock
    private InventoryAllocationRepository inventoryAllocationRepository;
    @Mock
    private InventoryManagementService inventoryManagementService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private ReservedProductCleanupService reservedProductCleanupService;

    @Test
    void cleanupExpiredReservations_ReleasesStockAndCancelsOrderAndPayment() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 5, 11, 10, 0);
        InventoryAllocation allocation = InventoryAllocation.builder()
                .id(1L)
                .referenceType(InventoryReferenceType.ORDER)
                .referenceId("ORD-123")
                .status(InventoryAllocationStatus.RESERVED)
                .quantity(2)
                .product(Product.builder().id(1L).name("Perfume A").stockQuantity(8).build())
                .build();
        Order order = Order.builder()
                .id(10L)
                .orderNumber("ORD-123")
                .status(PaymentStatus.PENDING)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .subtotal(new Money(BigDecimal.TEN, CurrencyCode.GHS))
                .totalAmount(new Money(BigDecimal.TEN, CurrencyCode.GHS))
                .build();
        Payment payment = Payment.builder()
                .id(20L)
                .order(order)
                .status(PaymentStatus.INITIATED)
                .customerEmail("customer@example.com")
                .systemReference("SYS-1")
                .amount(BigDecimal.TEN)
                .currency(CurrencyCode.GHS)
                .build();

        when(inventoryAllocationRepository.findByReferenceTypeAndStatusAndCreatedAtBefore(
                InventoryReferenceType.ORDER, InventoryAllocationStatus.RESERVED, cutoff))
                .thenReturn(List.of(allocation));
        when(orderRepository.findByOrderNumber("ORD-123")).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderNumber("ORD-123")).thenReturn(Optional.of(payment));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int cleaned = reservedProductCleanupService.cleanupExpiredReservations(cutoff);

        assertEquals(1, cleaned);
        verify(inventoryManagementService).releaseOrderInventory("ORD-123");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertEquals(PaymentStatus.CANCELLED, orderCaptor.getValue().getStatus());
        assertEquals(OrderProcessingStatus.CANCELLED, orderCaptor.getValue().getDeliveryStatus());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertEquals(PaymentStatus.CANCELLED, paymentCaptor.getValue().getStatus());
        assertEquals("Reservation expired after 1 hour", paymentCaptor.getValue().getFailureReason());
    }

    @Test
    void cleanupExpiredReservations_SkipsCompletedOrders() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 5, 11, 10, 0);
        InventoryAllocation allocation = InventoryAllocation.builder()
                .id(1L)
                .referenceType(InventoryReferenceType.ORDER)
                .referenceId("ORD-123")
                .status(InventoryAllocationStatus.RESERVED)
                .quantity(2)
                .product(Product.builder().id(1L).name("Perfume A").stockQuantity(8).build())
                .build();
        Order order = Order.builder()
                .id(10L)
                .orderNumber("ORD-123")
                .status(PaymentStatus.COMPLETED)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .build();

        when(inventoryAllocationRepository.findByReferenceTypeAndStatusAndCreatedAtBefore(
                InventoryReferenceType.ORDER, InventoryAllocationStatus.RESERVED, cutoff))
                .thenReturn(List.of(allocation));
        when(orderRepository.findByOrderNumber("ORD-123")).thenReturn(Optional.of(order));

        int cleaned = reservedProductCleanupService.cleanupExpiredReservations(cutoff);

        assertEquals(0, cleaned);
        verifyNoInteractions(inventoryManagementService, paymentRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredReservations_ReturnsZeroWhenNoStaleAllocations() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 5, 11, 10, 0);
        when(inventoryAllocationRepository.findByReferenceTypeAndStatusAndCreatedAtBefore(
                InventoryReferenceType.ORDER, InventoryAllocationStatus.RESERVED, cutoff))
                .thenReturn(List.of());

        int cleaned = reservedProductCleanupService.cleanupExpiredReservations(cutoff);

        assertEquals(0, cleaned);
        verifyNoInteractions(inventoryManagementService, orderRepository, paymentRepository);
    }
}

package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class OrderMapperPay01Test {

    private static Order order(PaymentStatus status) {
        return Order.builder().id(1L).orderNumber("ORD1")
                .user(User.builder().id(1L).deliveryAddresses(new ArrayList<>()).build())
                .subtotal(new Money(BigDecimal.TEN, CurrencyCode.GHS))
                .totalAmount(new Money(BigDecimal.TEN, CurrencyCode.GHS))
                .status(status).deliveryStatus(OrderProcessingStatus.PENDING)
                .items(new ArrayList<>()).taxes(new ArrayList<>())
                .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS)).build();
    }

    @Test
    void cancellableOnlyWhileUnpaid() {
        for (PaymentStatus s : PaymentStatus.values()) {
            boolean expected = s == PaymentStatus.PENDING || s == PaymentStatus.INITIATED;
            assertEquals(expected, OrderMapper.toOrderResponse(order(s)).cancellable(), s.name());
        }
    }

    @Test
    void referenceAndRefundRequiredComeFromThePayment() {
        Payment p = Payment.builder().providerPaymentReference("T123456").refundRequired(true).build();
        OrderResponse detail = OrderMapper.toOrderResponse(order(PaymentStatus.CANCELLED), p);
        OrderListResponse row = OrderMapper.toOrderListResponse(order(PaymentStatus.CANCELLED), p);
        assertEquals("T123456", detail.reference());
        assertTrue(detail.refundRequired());
        assertEquals("T123456", row.reference());
        assertTrue(row.refundRequired());

        Payment normal = Payment.builder().providerPaymentReference("T9999999").build();
        assertFalse(OrderMapper.toOrderResponse(order(PaymentStatus.COMPLETED), normal).refundRequired());
        assertFalse(OrderMapper.toOrderListResponse(order(PaymentStatus.COMPLETED), (Payment) null).refundRequired());
    }
}

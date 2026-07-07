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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderMapperTest {

    @Test
    void toOrderResponse_Success() {
        User user = User.builder().id(1L).deliveryAddresses(new ArrayList<>()).build();
        Order order = Order.builder()
                .id(1L).orderNumber("ORD1").user(user)
                .subtotal(new Money(BigDecimal.TEN, CurrencyCode.USD))
                .totalAmount(new Money(BigDecimal.TEN, CurrencyCode.USD))
                .status(PaymentStatus.COMPLETED)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .items(new ArrayList<>())
                .taxes(new ArrayList<>())
                .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .build();

        OrderResponse response = OrderMapper.toOrderResponse(order);

        assertNotNull(response);
        assertEquals("ORD1", response.orderNumber());
        assertEquals(PaymentStatus.COMPLETED, response.paymentStatus());
    }

    @Test
    void toOrderListResponse_Success() {
        Order order = Order.builder()
                .id(1L).orderNumber("ORD1")
                .status(PaymentStatus.COMPLETED)
                .deliveryStatus(OrderProcessingStatus.DELIVERED)
                .totalAmount(new Money(BigDecimal.TEN, CurrencyCode.USD))
                .build();

        OrderListResponse response = OrderMapper.toOrderListResponse(order);

        assertNotNull(response);
        assertEquals("ORD1", response.orderNumber());
        assertEquals(OrderProcessingStatus.DELIVERED, response.deliveryStatus());
    }

    @Test
    void toOrderResponse_NullUser_ThrowsException() {
        Order order = Order.builder().id(1L).build();
        assertThrows(NullPointerException.class, () -> OrderMapper.toOrderResponse(order));
    }
}

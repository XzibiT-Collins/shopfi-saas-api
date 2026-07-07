package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;

import java.math.BigDecimal;

public class OrderMapper {
    private OrderMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static OrderResponse toOrderResponse(Order order){

        Money subtotal = order.getSubtotal();
        BigDecimal autoDiscount = amount(order.getAutomaticDiscountAmount());
        Money originalSubtotal = new Money(amount(subtotal).add(autoDiscount), subtotal.getCurrencyCode());

        return OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .subtotal(subtotal.toString())
                .originalSubtotal(originalSubtotal.toString())
                .automaticDiscountAmount(
                        order.getAutomaticDiscountAmount() != null ?
                                order.getAutomaticDiscountAmount().toString() : "")
                .discountAmount(
                        order.getDiscountAmount() != null ?
                                order.getDiscountAmount().toString() : "")
                .totalAmount(order.getTotalAmount().toString())
                .paymentStatus(order.getStatus())
                .deliveryStatus(order.getDeliveryStatus())
                .orderDate(order.getCreatedAt())
                .lineItems(order.getItems()
                        .stream().map(OrderItemMapper::toOrderItemResponse)
                        .toList())
                .deliveryDetail(
                        order.getUser().getDeliveryAddresses().stream()
                                .filter(d -> Boolean.TRUE.equals(d.getIsDefault()))
                                .findFirst()
                                .map(DeliveryDetailMapper::toDeliveryDetailResponse)
                                .orElse(null)
                )
                .taxes(TaxCalculationResponse.builder()
                        .orderTaxes(order.getTaxes().stream()
                                .map(OrderTaxMapper::toOrderTaxResponse)
                                .toList())
                        .totalTaxAmount(order.getTotalTaxAmount() != null ? order.getTotalTaxAmount().toString() : "GHS 0.00")
                        .totalAmountAfterTax(order.getTotalAmount() != null ? order.getTotalAmount().toString() : "GHS 0.00")
                        .build())
                .build();
    }

    public static OrderListResponse toOrderListResponse(Order order){
        return OrderListResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .paymentStatus(order.getStatus())
                .deliveryStatus(order.getDeliveryStatus())
                .totalAmount(order.getTotalAmount().toString())
                .orderDate(order.getCreatedAt())
                .build();
    }

    private static BigDecimal amount(Money money) {
        return money != null && money.getAmount() != null ? money.getAmount() : BigDecimal.ZERO;
    }
}

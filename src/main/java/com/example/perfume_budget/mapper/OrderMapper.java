package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;

import java.math.BigDecimal;

public class OrderMapper {
    private OrderMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static OrderResponse toOrderResponse(Order order){
        return toOrderResponse(order, (String) null);
    }

    /** @param reference the Paystack reference of the order's payment (null when unknown / not initialised) */
    public static OrderResponse toOrderResponse(Order order, Payment payment){
        return toOrderResponse(order, payment == null ? null : payment.getProviderPaymentReference(),
                payment != null && payment.isRefundRequired());
    }

    public static OrderResponse toOrderResponse(Order order, String reference){
        return toOrderResponse(order, reference, false);
    }

    public static OrderResponse toOrderResponse(Order order, String reference, boolean refundRequired){

        Money subtotal = order.getSubtotal();
        BigDecimal autoDiscount = amount(order.getAutomaticDiscountAmount());
        Money originalSubtotal = new Money(amount(subtotal).add(autoDiscount), subtotal.getCurrencyCode());

        return OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .reference(reference)
                .refundRequired(refundRequired)
                .cancellable(isCancellable(order))
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
                        order.getDeliveryDetail() != null
                                ? DeliveryDetailMapper.toDeliveryDetailResponse(order.getDeliveryDetail())
                                : order.getUser().getDeliveryAddresses().stream()
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
        return toOrderListResponse(order, (String) null);
    }

    public static OrderListResponse toOrderListResponse(Order order, Payment payment){
        return toOrderListResponse(order, payment == null ? null : payment.getProviderPaymentReference(),
                payment != null && payment.isRefundRequired());
    }

    public static OrderListResponse toOrderListResponse(Order order, String reference){
        return toOrderListResponse(order, reference, false);
    }

    public static OrderListResponse toOrderListResponse(Order order, String reference, boolean refundRequired){
        return OrderListResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .reference(reference)
                .refundRequired(refundRequired)
                .paymentStatus(order.getStatus())
                .deliveryStatus(order.getDeliveryStatus())
                .totalAmount(order.getTotalAmount().toString())
                .orderDate(order.getCreatedAt())
                .build();
    }

    /** The owner may cancel only while payment has not settled: PENDING or INITIATED. */
    public static boolean isCancellable(Order order) {
        return order.getStatus() == com.example.perfume_budget.enums.PaymentStatus.PENDING
                || order.getStatus() == com.example.perfume_budget.enums.PaymentStatus.INITIATED;
    }

    private static BigDecimal amount(Money money) {
        return money != null && money.getAmount() != null ? money.getAmount() : BigDecimal.ZERO;
    }
}

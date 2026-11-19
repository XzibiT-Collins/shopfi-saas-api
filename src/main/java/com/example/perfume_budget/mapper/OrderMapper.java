package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order.OrderAccountResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    /**
     * Owner detail view (PAY-03 rework): the payment overload plus the Paystack {@code authorizationUrl}, which the
     * CALLER has already decided is safe to show (owner, order open); pass null otherwise.
     */
    public static OrderResponse toOwnerOrderResponse(Order order, Payment payment, String authorizationUrl){
        OrderResponse r = toOrderResponse(order, payment);
        return authorizationUrl == null ? r : r.toBuilder().authorizationUrl(authorizationUrl).build();
    }

    /** Admin view: same as the payment overload plus the owner's identity. */
    public static OrderResponse toAdminOrderResponse(Order order, Payment payment){
        return toOrderResponse(order, payment == null ? null : payment.getProviderPaymentReference(),
                payment != null && payment.isRefundRequired(), true);
    }

    public static OrderResponse toOrderResponse(Order order, String reference, boolean refundRequired){
        return toOrderResponse(order, reference, refundRequired, false);
    }

    /** The order's creation instant as UTC with an explicit offset (the stored value is server-local, zone-less). */
    public static OffsetDateTime toUtc(LocalDateTime local){
        return local == null ? null : local.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
    }

    private static OrderResponse toOrderResponse(Order order, String reference, boolean refundRequired, boolean includeAccount){

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
                .orderedAt(toUtc(order.getCreatedAt()))
                .version(order.getVersion())
                .cancelledAt(toUtc(order.getCancelledAt()))
                .cancelSource(order.getCancelSource() == null ? null : order.getCancelSource().name())
                .returnedAt(toUtc(order.getReturnedAt()))
                .account(includeAccount && order.getUser() != null
                        ? OrderAccountResponse.builder().id(order.getUser().getId())
                        .fullName(order.getUser().getFullName()).email(order.getUser().getEmail()).build()
                        : null)
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
                .cancellable(isCancellable(order))
                .paymentStatus(order.getStatus())
                .deliveryStatus(order.getDeliveryStatus())
                .totalAmount(order.getTotalAmount().toString())
                .orderDate(order.getCreatedAt())
                .orderedAt(toUtc(order.getCreatedAt()))
                .version(order.getVersion())
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

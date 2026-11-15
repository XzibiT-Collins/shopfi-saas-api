package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.dto.order_item.OrderItemResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Builder
public record OrderResponse(
        Long orderId,
        String orderNumber,
        String reference,             // Paystack reference of this order's payment (what /checkout/callback?reference= carries); null before initialisation
        String subtotal,              // net of automatic (product/shop) discount
        String originalSubtotal,      // gross, before automatic discount
        String automaticDiscountAmount, // product/shop discount baked into subtotal
        String discountAmount,        // coupon discount
        String totalAmount,
        PaymentStatus paymentStatus,
        OrderProcessingStatus deliveryStatus,
        List<OrderItemResponse> lineItems,
        /** DEPRECATED: zone-less server-local time; use {@code orderedAt}. */
        LocalDateTime orderDate,
        OffsetDateTime orderedAt,     // the same instant in UTC with an explicit offset (ISO-8601, "...Z")
        DeliveryDetailResponse deliveryDetail,
        TaxCalculationResponse taxes,
        boolean refundRequired,       // paid but the order could not be fulfilled any more: the merchant must refund
        boolean cancellable,          // true while the owner may POST /api/v1/order/{orderNumber}/cancel
        Long version,                 // optimistic-concurrency token for PUT /order/update-order-status (also the ETag)
        OffsetDateTime cancelledAt,   // UTC instant the order was cancelled, null while it is not
        String cancelSource,          // CUSTOMER | ADMIN | EXPIRY, null while not cancelled (and for legacy cancels)
        @JsonInclude(JsonInclude.Include.NON_NULL) OrderAccountResponse account // ADMIN responses only: owner identity; the key is absent for customers
) {}

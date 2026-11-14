package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.dto.order_item.OrderItemResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import lombok.Builder;

import java.time.LocalDateTime;
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
        LocalDateTime orderDate,
        DeliveryDetailResponse deliveryDetail,
        TaxCalculationResponse taxes,
        boolean refundRequired,       // paid but the order could not be fulfilled any more: the merchant must refund
        boolean cancellable           // true while the owner may POST /api/v1/order/{orderNumber}/cancel
) {}

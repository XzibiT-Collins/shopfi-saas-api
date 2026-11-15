package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.order.OrderStatusUpdateRequest;
import com.example.perfume_budget.dto.checkout.CheckoutResponse;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface OrderService {
    // CUSTOMER
    /**
     * @param deliveryDetailId optional explicit delivery detail of the caller (default address when null)
     * @param idempotencyKey   optional client key; a retry with the same key and request returns the same order
     */
    CheckoutResponse checkout(String couponCode, Long deliveryDetailId, String idempotencyKey);

    /** Owner cancel of an unpaid order (404 when not the caller's). Idempotent. */
    OrderResponse cancelMyOrder(String orderNumber);
    PageResponse<OrderListResponse> getMyOrders(Pageable pageable);

    OrderResponse getOrderByOrderNumber(String orderNumber);

    // ADMIN
    /**
     * @param from   inclusive lower bound of the order time (ISO-8601 date or offset date-time), null = none
     * @param to     upper bound: a date is inclusive of that whole (UTC) day, a date-time is exclusive; null = none
     * @param search text search over order number, recipient name, phone and account e-mail (contains, case-insensitive)
     */
    PageResponse<OrderListResponse> getAllOrders(PaymentStatus paymentStatus, OrderProcessingStatus deliveryStatus,
                                                 String from, String to, String search, Pageable pageable);
    /** @param ifMatchVersion version from the If-Match header, merged with the body's {@code version} (they must agree) */
    OrderResponse updateOrderStatus(String orderNumber, OrderStatusUpdateRequest request, Long ifMatchVersion);
    /** The shop refunded a refund-required order by hand: clears the flag and records who/when. */
    OrderResponse acknowledgeRefund(String orderNumber);

    void reserveStock(String orderNumber, List<OrderItem> orderItems);

    void releaseStock(String orderNumber);

    void finalizeReservedStock(Order order);
}


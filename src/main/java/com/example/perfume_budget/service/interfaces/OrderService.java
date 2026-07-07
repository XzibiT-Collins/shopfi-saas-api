package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.order.OrderStatusUpdateRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface OrderService {
    // CUSTOMER
    PaystackInitiateTransactionResponse checkout(String couponCode);
    PageResponse<OrderListResponse> getMyOrders(Pageable pageable);

    OrderResponse getOrderByOrderNumber(String orderNumber);

    // ADMIN
    PageResponse<OrderListResponse> getAllOrders(PaymentStatus paymentStatus, OrderProcessingStatus deliveryStatus, Pageable pageable);
    OrderResponse updateOrderStatus(String orderNumber, OrderStatusUpdateRequest request);

    void reserveStock(String orderNumber, List<OrderItem> orderItems);

    void releaseStock(String orderNumber);

    void finalizeReservedStock(Order order);
}


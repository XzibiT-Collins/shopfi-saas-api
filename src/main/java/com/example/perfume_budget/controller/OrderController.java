package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.order.OrderStatusUpdateRequest;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.service.interfaces.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/order")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/my-orders")
    public ResponseEntity<CustomApiResponse<PageResponse<OrderListResponse>>> getMyOrders(Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.getMyOrders(pageable))
        );
    }

    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping("/{orderNumber}")
    public ResponseEntity<CustomApiResponse<OrderResponse>> getOrder(@PathVariable String orderNumber){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.getOrderByOrderNumber(orderNumber))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/all")
    public ResponseEntity<CustomApiResponse<PageResponse<OrderListResponse>>> getAllOrders(
            @RequestParam(required = false, name = "paymentStatus") PaymentStatus paymentStatus,
            @RequestParam(required = false, name = "orderStatus") OrderProcessingStatus orderStatus,
            Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.getAllOrders(paymentStatus, orderStatus, pageable))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update-order-status/{orderNumber}")
    public ResponseEntity<CustomApiResponse<OrderResponse>> updateOrderStatus(@PathVariable String orderNumber, @Valid @RequestBody OrderStatusUpdateRequest request){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.updateOrderStatus(orderNumber, request))
        );
    }

}

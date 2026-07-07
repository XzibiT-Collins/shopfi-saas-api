package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.service.interfaces.WalkInOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/walk-in")
@RequiredArgsConstructor
public class WalkInOrderController {
    private final WalkInOrderService walkInOrderService;

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @PostMapping("/order")
    public ResponseEntity<CustomApiResponse<WalkInOrderResponse>> placeWalkInOrder(
            @Valid @RequestBody WalkInOrderRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.placeWalkInOrder(request)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping("/customers/search")
    public ResponseEntity<CustomApiResponse<List<CustomerSearchResponse>>> searchCustomers(@RequestParam String query) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.searchCustomers(query)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping("/orders")
    public ResponseEntity<CustomApiResponse<PageResponse<WalkInOrderResponse>>> getWalkInOrders(
            @RequestParam(required = false) LocalDate date,
            Pageable pageable) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.getWalkInOrders(date, pageable)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping("/orders/{orderNumber}")
    public ResponseEntity<CustomApiResponse<WalkInOrderResponse>> getWalkInOrder(@PathVariable String orderNumber) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.getWalkInOrder(orderNumber)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @PatchMapping("/orders/{orderNumber}/receipt-printed")
    public ResponseEntity<CustomApiResponse<Void>> markReceiptPrinted(@PathVariable String orderNumber) {
        walkInOrderService.markReceiptPrinted(orderNumber);
        return ResponseEntity.ok(CustomApiResponse.success(null));
    }
}

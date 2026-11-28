package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.RequiresFeature;
import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.walk_in.request.CustomerSearchRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInReversalRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteResponse;
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

    @RequiresFeature(PlanFeature.WALK_IN_POS)
    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @PostMapping("/order")
    public ResponseEntity<CustomApiResponse<WalkInOrderResponse>> placeWalkInOrder(
            @Valid @RequestBody WalkInOrderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.placeWalkInOrder(request, idempotencyKey)));
    }

    /** API-RD-03 AC1: server-computed totals of a draft; writes nothing. */
    @RequiresFeature(PlanFeature.WALK_IN_POS)
    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @PostMapping("/quote")
    public ResponseEntity<CustomApiResponse<WalkInQuoteResponse>> quoteWalkInOrder(
            @Valid @RequestBody WalkInQuoteRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.quote(request)));
    }

    /** API-RD-13(b): the search text travels in the body, so it never reaches an access log or browser history. */
    @RequiresFeature(value = PlanFeature.WALK_IN_POS, mode = RequiresFeature.Mode.READ)
    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @PostMapping("/customers/search")
    public ResponseEntity<CustomApiResponse<List<CustomerSearchResponse>>> searchCustomersByBody(
            @Valid @RequestBody CustomerSearchRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.searchCustomers(request.query())));
    }

    @RequiresFeature(PlanFeature.WALK_IN_POS)
    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping("/customers/search")
    @Deprecated
    public ResponseEntity<CustomApiResponse<List<CustomerSearchResponse>>> searchCustomers(@RequestParam String query) {
        // DEPRECATED (API-RD-13b): the query string lands in access logs. Kept only until the register moves to POST.
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Link", "</api/v1/admin/walk-in/customers/search>; rel=\"successor-version\"; title=\"POST with a JSON body\"")
                .body(CustomApiResponse.success(walkInOrderService.searchCustomers(query)));
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

    /** API-RD-03 AC3: ADMIN only (no front-desk permission exists for it), Idempotency-Key required. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/orders/{orderNumber}/cancel")
    public ResponseEntity<CustomApiResponse<WalkInOrderResponse>> cancelWalkInOrder(
            @PathVariable String orderNumber,
            @Valid @RequestBody WalkInReversalRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.cancelWalkInOrder(orderNumber, request, idempotencyKey)));
    }

    /** API-RD-03 AC3: full refund, ADMIN only, Idempotency-Key required. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/orders/{orderNumber}/refund")
    public ResponseEntity<CustomApiResponse<WalkInOrderResponse>> refundWalkInOrder(
            @PathVariable String orderNumber,
            @Valid @RequestBody WalkInReversalRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(CustomApiResponse.success(walkInOrderService.refundWalkInOrder(orderNumber, request, idempotencyKey)));
    }
}

package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderAuditEventResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    /** Owner cancel of an unpaid order; releases its stock reservation once. 404 for anyone else's order. */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNumber}/cancel")
    public ResponseEntity<CustomApiResponse<OrderResponse>> cancelOrder(@PathVariable String orderNumber){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.cancelMyOrder(orderNumber))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/all")
    public ResponseEntity<CustomApiResponse<PageResponse<OrderListResponse>>> getAllOrders(
            @RequestParam(required = false, name = "paymentStatus") PaymentStatus paymentStatus,
            @RequestParam(required = false, name = "orderStatus") OrderProcessingStatus orderStatus,
            @RequestParam(required = false, name = "from") String from,
            @RequestParam(required = false, name = "to") String to,
            @RequestParam(required = false, name = "q") String q,
            Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.getAllOrders(paymentStatus, orderStatus, from, to, q, pageable))
        );
    }

    /**
     * Admin delivery-status change (docs/api/orders.md). The response carries {@code version}, also as the
     * {@code ETag} header; send it back as {@code If-Match} or in the body to detect a concurrent change.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update-order-status/{orderNumber}")
    public ResponseEntity<CustomApiResponse<OrderResponse>> updateOrderStatus(
            @PathVariable String orderNumber,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody OrderStatusUpdateRequest request){
        OrderResponse response = orderService.updateOrderStatus(orderNumber, request, parseIfMatch(ifMatch));
        return etag(response).body(CustomApiResponse.success(response));
    }

    /** The shop refunded a refund-required order by hand; 409 REFUND_NOT_REQUIRED when there is nothing to acknowledge. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{orderNumber}/refund-acknowledgement")
    public ResponseEntity<CustomApiResponse<OrderResponse>> acknowledgeRefund(@PathVariable String orderNumber){
        OrderResponse response = orderService.acknowledgeRefund(orderNumber);
        return etag(response).body(CustomApiResponse.success(response));
    }

    /**
     * ORD-02: the goods of a paid order cancelled while OUT_FOR_DELIVERY are back in the shop: restock them once.
     * 409 RETURN_NOT_APPLICABLE / NOTHING_TO_RESTOCK (docs/api/orders.md).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{orderNumber}/return-received")
    public ResponseEntity<CustomApiResponse<OrderResponse>> receiveReturn(@PathVariable String orderNumber){
        OrderResponse response = orderService.receiveReturn(orderNumber);
        return etag(response).body(CustomApiResponse.success(response));
    }

    /**
     * ORD-07: records the return of a whole DELIVERED order: restock, sale reversed in the books, refund now required
     * (acknowledge it with the refund-acknowledgement route). Idempotent. 409 ORDER_NOT_RETURNABLE /
     * RETURN_WINDOW_EXPIRED (docs/api/orders.md).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{orderNumber}/return")
    public ResponseEntity<CustomApiResponse<OrderResponse>> returnDeliveredOrder(@PathVariable String orderNumber){
        OrderResponse response = orderService.returnDeliveredOrder(orderNumber);
        return etag(response).body(CustomApiResponse.success(response));
    }

    /** ORD-02: the order's append-only audit trail, oldest first, paged (size capped at 50). */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{orderNumber}/audit-events")
    public ResponseEntity<CustomApiResponse<PageResponse<OrderAuditEventResponse>>> getAuditEvents(
            @PathVariable String orderNumber, Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.getAuditEvents(orderNumber, pageable))
        );
    }

    private static ResponseEntity.BodyBuilder etag(OrderResponse response) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (response.version() != null) {
            b.eTag("\"" + response.version() + "\"");
        }
        return b;
    }

    /**
     * ORD-04: the only request body on this controller is the status change; a body that cannot be turned into a status
     * change answers 400 with a stable {@code data.code} (descriptions are unchanged): {@code INVALID_ORDER_STATUS}
     * for an unknown value or a missing/null {@code orderStatus} (with `knownValues`), {@code INVALID_REQUEST_BODY}
     * for a missing or unreadable body. Nothing the client sent is echoed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomApiResponse<Map<String, Object>>> unreadableBody(HttpMessageNotReadableException ex) {
        for (Throwable t = ex.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException f
                    && f.getTargetType() == OrderProcessingStatus.class) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(
                        "Request body is missing or malformed.", invalidStatusData()));
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(
                "Request body is missing or malformed.", Map.of("code", "INVALID_REQUEST_BODY")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomApiResponse<Map<String, Object>>> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e ->
                data.put(e.getField(), e.isBindingFailure() ? "Invalid value" : e.getDefaultMessage()));
        if (ex.getBindingResult().getFieldErrors().stream().anyMatch(e -> "orderStatus".equals(e.getField()))) {
            data.putAll(invalidStatusData());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Validation failed", data));
    }

    private static Map<String, Object> invalidStatusData() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("code", "INVALID_ORDER_STATUS");
        data.put("knownValues", java.util.Arrays.stream(OrderProcessingStatus.values()).map(Enum::name).toList());
        return data;
    }

    /** {@code "3"}, {@code W/"3"} or {@code 3}; anything else is a 400 (a wildcard is not accepted). */
    static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            long n = Long.parseLong(v);
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new com.example.perfume_budget.exception.ApiCodeException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_IF_MATCH", "If-Match must be the order version, e.g. \"3\".");
        }
    }

}

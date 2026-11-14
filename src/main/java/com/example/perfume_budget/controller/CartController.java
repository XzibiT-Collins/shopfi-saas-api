package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.request.PopulateCartItemRequest;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.checkout.CartPreviewRequest;
import com.example.perfume_budget.dto.checkout.CartPreviewResponse;
import com.example.perfume_budget.dto.checkout.CheckoutResponse;
import com.example.perfume_budget.service.CartPreviewService;
import com.example.perfume_budget.service.interfaces.CartService;
import com.example.perfume_budget.service.interfaces.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;
    private final OrderService orderService;
    private final CartPreviewService cartPreviewService;

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<CartResponse>> getMyCart(){
        return ResponseEntity.ok().body(CustomApiResponse.success(cartService.getCart()));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @DeleteMapping("/clear")
    public ResponseEntity<Void> clearMyCart(){
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/populate")
    public ResponseEntity<CustomApiResponse<CartResponse>> populateCartFromLocalStorage(
            @Valid @RequestBody PopulateCartItemRequest itemRequestList){
        return ResponseEntity.ok().body(CustomApiResponse
                .success(cartService.populateCartFromLocalStorage(itemRequestList)));
    }

    /**
     * Creates the order and initialises the Paystack payment. Optional {@code Idempotency-Key} header: a retry with the
     * same key (and the same cart/coupon/address) returns the same order and payment URL instead of a second order.
     * See docs/api/checkout.md.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/checkout")
    public ResponseEntity<CustomApiResponse<CheckoutResponse>> checkout(
            @RequestParam(name = "couponCode", required = false) String couponCode,
            @RequestParam(name = "deliveryDetailId", required = false) Long deliveryDetailId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.checkout(couponCode, deliveryDetailId, idempotencyKey))
        );
    }

    /** Server-computed totals (and coupon validation) for the current cart; creates nothing. */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/preview")
    public ResponseEntity<CustomApiResponse<CartPreviewResponse>> preview(
            @Valid @RequestBody(required = false) CartPreviewRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(cartPreviewService.preview(request)));
    }
}

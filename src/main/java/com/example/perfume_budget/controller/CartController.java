package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.request.PopulateCartItemRequest;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
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

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/checkout")
    public ResponseEntity<CustomApiResponse<PaystackInitiateTransactionResponse>> checkout(
            @RequestParam(name = "couponCode", required = false) String couponCode){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(orderService.checkout(couponCode))
        );
    }
}

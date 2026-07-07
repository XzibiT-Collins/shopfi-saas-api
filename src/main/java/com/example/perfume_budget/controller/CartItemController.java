package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.request.CartItemUpdateRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.service.interfaces.CartItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart/items")
@RequiredArgsConstructor
public class CartItemController {
    private final CartItemService cartItemService;

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/add-item")
    public ResponseEntity<CustomApiResponse<CartItemResponse>> addItemToCart(@Valid @RequestBody CartItemRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(cartItemService.addItemToCart(request)));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PutMapping("/update/{cartItemId}")
    public ResponseEntity<CustomApiResponse<CartItemResponse>> updateCartItem(@PathVariable Long cartItemId, @Valid @RequestBody CartItemUpdateRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(cartItemService.updateCartItem(cartItemId, request)));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @DeleteMapping("/remove/{cartItemId}")
    public ResponseEntity<Void> removeItemFromCart(@PathVariable Long cartItemId){
        cartItemService.removeCartItem(cartItemId);
        return ResponseEntity.noContent().build();
    }
}

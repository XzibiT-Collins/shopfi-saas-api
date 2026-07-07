package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;
import com.example.perfume_budget.service.interfaces.ShopWideDiscountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/shop-discount")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ShopWideDiscountController {
    private final ShopWideDiscountService shopWideDiscountService;

    @PutMapping
    public ResponseEntity<CustomApiResponse<ShopWideDiscountResponse>> setShopWideDiscount(
            @Valid @RequestBody ShopWideDiscountRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(shopWideDiscountService.setShopWideDiscount(request)));
    }

    @GetMapping("/current")
    public ResponseEntity<CustomApiResponse<ShopWideDiscountResponse>> getCurrent(){
        return ResponseEntity.ok().body(CustomApiResponse.success(shopWideDiscountService.getCurrent()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id){
        shopWideDiscountService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}

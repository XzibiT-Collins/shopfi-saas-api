package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.RequiresFeature;
import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;
import com.example.perfume_budget.service.interfaces.ShopWideDiscountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/shop-discount")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ShopWideDiscountController {
    private final ShopWideDiscountService shopWideDiscountService;

    @RequiresFeature(PlanFeature.SHOP_WIDE_DISCOUNTS)
    @PutMapping
    public ResponseEntity<CustomApiResponse<ShopWideDiscountResponse>> setShopWideDiscount(
            @Valid @RequestBody ShopWideDiscountRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(shopWideDiscountService.setShopWideDiscount(request)));
    }

    /**
     * API-06: the discounts that are switched on and not over yet, in force now OR scheduled to start later, soonest
     * start first ({@code []} when none). Unlike {@code /current} (unchanged: only a discount in force, else 404) this
     * lets the admin read a scheduled discount back and end it with {@code DELETE /{id}} before it starts.
     */
    @GetMapping
    public ResponseEntity<CustomApiResponse<List<ShopWideDiscountResponse>>> listNotEnded(){
        return ResponseEntity.ok().body(CustomApiResponse.success(shopWideDiscountService.listNotEnded()));
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

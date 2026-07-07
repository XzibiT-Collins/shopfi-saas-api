package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.product.response.AvailableUomsResponse;
import com.example.perfume_budget.dto.product.response.ProductFamilySummaryResponse;
import com.example.perfume_budget.service.interfaces.ProductFamilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/product-families")
@RequiredArgsConstructor
public class ProductFamilyController {

    private final ProductFamilyService productFamilyService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<List<ProductFamilySummaryResponse>>> getAllFamilies() {
        return ResponseEntity.ok(CustomApiResponse.success("Product families retrieved", productFamilyService.getAllFamilies()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}/available-uoms")
    public ResponseEntity<CustomApiResponse<AvailableUomsResponse>> getAvailableUoms(@PathVariable Long id) {
        return ResponseEntity.ok(CustomApiResponse.success("Available UOMs retrieved", productFamilyService.getAvailableUoms(id)));
    }
}

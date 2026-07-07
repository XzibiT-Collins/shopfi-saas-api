package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.inventory.request.InventoryAdjustmentRequest;
import com.example.perfume_budget.dto.inventory.request.InventoryReceiptRequest;
import com.example.perfume_budget.dto.inventory.response.InventoryMovementResponse;
import com.example.perfume_budget.dto.inventory.response.InventorySummaryResponse;
import com.example.perfume_budget.dto.inventory.response.ProductStockByLocationResponse;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/inventory")
@RequiredArgsConstructor
public class InventoryManagementController {
    private final InventoryManagementService inventoryManagementService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/receipts")
    public ResponseEntity<CustomApiResponse<InventorySummaryResponse>> receiveStock(
            @Valid @RequestBody InventoryReceiptRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Inventory receipt recorded",
                inventoryManagementService.receiveStock(request)
        ));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/adjustments")
    public ResponseEntity<CustomApiResponse<InventorySummaryResponse>> adjustInventory(
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Inventory change recorded",
                inventoryManagementService.adjustInventory(request)
        ));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/products/{productId}/summary")
    public ResponseEntity<CustomApiResponse<InventorySummaryResponse>> getProductInventorySummary(
            @PathVariable Long productId) {
        return ResponseEntity.ok(CustomApiResponse.success(
                inventoryManagementService.getProductInventorySummary(productId)
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @GetMapping("/products/{productId}/stock-by-location")
    public ResponseEntity<CustomApiResponse<ProductStockByLocationResponse>> getProductStockByLocation(
            @PathVariable Long productId) {
        return ResponseEntity.ok(CustomApiResponse.success(
                inventoryManagementService.getProductStockByLocation(productId)
        ));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/products/{productId}/history")
    public ResponseEntity<CustomApiResponse<List<InventoryMovementResponse>>> getProductInventoryHistory(
            @PathVariable Long productId) {
        return ResponseEntity.ok(CustomApiResponse.success(
                inventoryManagementService.getProductInventoryHistory(productId)
        ));
    }
}

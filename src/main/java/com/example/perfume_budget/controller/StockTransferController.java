package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.inventory.request.StockTransferRequest;
import com.example.perfume_budget.dto.inventory.response.StockTransferResponse;
import com.example.perfume_budget.service.interfaces.StockTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/inventory/transfers")
@RequiredArgsConstructor
public class StockTransferController {
    private final StockTransferService stockTransferService;

    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @PostMapping
    public ResponseEntity<CustomApiResponse<StockTransferResponse>> transfer(
            @Valid @RequestBody StockTransferRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Stock transfer recorded",
                stockTransferService.transfer(request)
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<Page<StockTransferResponse>>> history(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(CustomApiResponse.success(
                stockTransferService.history(productId, locationId, page, size)
        ));
    }
}

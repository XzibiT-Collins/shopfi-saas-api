package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.RequiresFeature;
import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.product.request.StockConversionRequest;
import com.example.perfume_budget.dto.product.response.ProductVariantSummaryResponse;
import com.example.perfume_budget.dto.product.response.StockConversionResponse;
import com.example.perfume_budget.service.interfaces.StockConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/stock-conversions")
@RequiredArgsConstructor
public class StockConversionController {

    private final StockConversionService stockConversionService;

    @RequiresFeature(PlanFeature.STOCK_CONVERSION)
    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @GetMapping("/reverse/{sourceProductId}/variants")
    public ResponseEntity<CustomApiResponse<List<ProductVariantSummaryResponse>>> getReverseConversionTargetVariants(
            @PathVariable Long sourceProductId) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Reverse conversion variants retrieved",
                stockConversionService.getReverseConversionTargetVariants(sourceProductId)
        ));
    }

    @RequiresFeature(PlanFeature.STOCK_CONVERSION)
    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @PostMapping("/forward")
    public ResponseEntity<CustomApiResponse<StockConversionResponse>> convertForward(
            @Valid @RequestBody StockConversionRequest request) {
        StockConversionResponse conversion = stockConversionService.convertForward(
                request.sourceProductId(),
                request.quantity(),
                request.notes()
        );
        return ResponseEntity.ok(CustomApiResponse.success("Forward conversion successful", conversion));
    }

    @RequiresFeature(PlanFeature.STOCK_CONVERSION)
    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @PostMapping("/reverse")
    public ResponseEntity<CustomApiResponse<StockConversionResponse>> convertReverse(
            @Valid @RequestBody StockConversionRequest request) {
        StockConversionResponse conversion = stockConversionService.convertReverse(
                request.sourceProductId(),
                request.quantity(),
                request.targetProductId(),
                request.notes()
        );
        return ResponseEntity.ok(CustomApiResponse.success("Reverse conversion successful", conversion));
    }
}

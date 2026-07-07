package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.sales.ItemsSoldReportResponse;
import com.example.perfume_budget.dto.sales.ProductSalesDetailResponse;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.service.interfaces.SalesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/inventory/sales")
@RequiredArgsConstructor
public class SalesReportController {

    private final SalesReportService salesReportService;

    @GetMapping("/items-sold")
    public ResponseEntity<CustomApiResponse<ItemsSoldReportResponse>> getItemsSold(
            @RequestParam(defaultValue = "ALL") SalesAnalyticsSource source,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            Pageable pageable) {
        return ResponseEntity.ok(CustomApiResponse.success(
                salesReportService.getItemsSold(source, from, to, pageable)));
    }

    @GetMapping("/items-sold/{productId}")
    public ResponseEntity<CustomApiResponse<ProductSalesDetailResponse>> getProductSales(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "ALL") SalesAnalyticsSource source,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            Pageable pageable) {
        return ResponseEntity.ok(CustomApiResponse.success(
                salesReportService.getProductSales(productId, source, from, to, pageable)));
    }
}

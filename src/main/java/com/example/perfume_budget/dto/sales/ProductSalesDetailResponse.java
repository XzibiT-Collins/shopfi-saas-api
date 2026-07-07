package com.example.perfume_budget.dto.sales;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.SalesAnalyticsSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Per-product sale detail: every individual sale of one product in [from, to]. */
public record ProductSalesDetailResponse(
        Long productId,
        String productName,
        String sku,
        String imageUrl,
        Integer remainingStockGlobal,
        List<LocationStockEntry> remainingByLocation,
        LocalDate from,
        LocalDate to,
        SalesAnalyticsSource source,
        PageResponse<SaleLineResponse> sales,
        long totalQuantitySold,
        BigDecimal totalAmountSold,
        CurrencyCode currency
) {
}

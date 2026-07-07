package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.sales.ItemsSoldReportResponse;
import com.example.perfume_budget.dto.sales.ProductSalesDetailResponse;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface SalesReportService {

    /** Products sold in [from, to] (default: today) with quantity/amount totals and current stock. */
    ItemsSoldReportResponse getItemsSold(SalesAnalyticsSource source, LocalDate from, LocalDate to, Pageable pageable);

    /** Every individual sale of one product in [from, to] (default: today). */
    ProductSalesDetailResponse getProductSales(Long productId, SalesAnalyticsSource source,
                                               LocalDate from, LocalDate to, Pageable pageable);
}

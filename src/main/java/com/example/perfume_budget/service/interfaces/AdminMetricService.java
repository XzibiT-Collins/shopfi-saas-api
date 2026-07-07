package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.coupon.response.CouponMetricResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.customer.CustomerFullDetailsResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.projection.LowStockProduct;
import com.example.perfume_budget.projection.TopCustomer;
import jakarta.annotation.Nullable;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface AdminMetricService {
    PageResponse<CustomerDataResponse> getCustomers(Pageable pageable);
    PageResponse<CustomerDataResponse> searchCustomer(Pageable pageable, String keyword);
    CouponMetricResponse getCouponMetrics();
    List<MostPurchaseProductResponse> getMostPurchasedProduct();
    SiteVisitMetric getSiteVisitMetric(@Nullable LocalDate from, @Nullable LocalDate to);
    DashboardMetrics getDashboardMetrics();
    List<LowStockProduct> getLowStockProducts();
    List<TopCustomer> getTopCustomers();
    CustomerFullDetailsResponse getCustomerDetails(Long userId);
    PageResponse<OrderListResponse> getCustomerOrders(Long userId, Pageable pageable);
    SalesAnalyticsResponse getSalesAnalytics(
            SalesAnalyticsSource source,
            SalesAnalyticsGranularity granularity,
            LocalDate from,
            LocalDate to);
}

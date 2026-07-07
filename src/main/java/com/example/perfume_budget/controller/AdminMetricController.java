package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.dto.coupon.response.CouponMetricResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.customer.CustomerFullDetailsResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.projection.LowStockProduct;
import com.example.perfume_budget.projection.TopCustomer;
import com.example.perfume_budget.service.interfaces.AdminMetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricController {
    private final AdminMetricService adminMetricService;

    @GetMapping("/customers")
    public ResponseEntity<CustomApiResponse<PageResponse<CustomerDataResponse>>> getCustomerData(Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getCustomers(pageable))
        );
    }

    @GetMapping("/customers/{userId}")
    public ResponseEntity<CustomApiResponse<CustomerFullDetailsResponse>> getCustomerDetails(@PathVariable Long userId){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getCustomerDetails(userId))
        );
    }

    @GetMapping("/customers/{userId}/orders")
    public ResponseEntity<CustomApiResponse<PageResponse<OrderListResponse>>> getCustomerOrders(
            @PathVariable Long userId,
            Pageable pageable
    ){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getCustomerOrders(userId, pageable))
        );
    }

    @GetMapping("/coupons")
    public ResponseEntity<CustomApiResponse<CouponMetricResponse>> getCouponMetrics(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getCouponMetrics())
        );
    }

    @GetMapping("/top-products")
    public ResponseEntity<CustomApiResponse<List<MostPurchaseProductResponse>>> getTopProducts(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getMostPurchasedProduct())
        );
    }

    @GetMapping("/site-metric")
    public ResponseEntity<CustomApiResponse<SiteVisitMetric>> getSiteMetric(
            @RequestParam(name = "from", required = false)LocalDate from,
            @RequestParam(name = "to", required = false)LocalDate to
            ){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getSiteVisitMetric(from, to))
        );
    }

    @GetMapping("/dashboard-metric")
    public ResponseEntity<CustomApiResponse<DashboardMetrics>> getDashboardMetric(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getDashboardMetrics())
        );
    }
    
    @GetMapping("/low-stock-products")
    public ResponseEntity<CustomApiResponse<List<LowStockProduct>>> getLowStockProducts(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getLowStockProducts())
        );
    }

    @GetMapping("/top-customers")
    public ResponseEntity<CustomApiResponse<List<TopCustomer>>> getTopCustomers(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(adminMetricService.getTopCustomers())
        );
    }

    @GetMapping("/sales-analytics")
    public ResponseEntity<CustomApiResponse<SalesAnalyticsResponse>> getSalesAnalytics(
            @RequestParam(defaultValue = "ALL") SalesAnalyticsSource source,
            @RequestParam(defaultValue = "DAY") SalesAnalyticsGranularity granularity,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        if ((from == null) != (to == null)) {
            throw new BadRequestException("Both 'from' and 'to' must be provided together");
        }
        if (from != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }

        return ResponseEntity.ok(
                CustomApiResponse.success(
                        adminMetricService.getSalesAnalytics(source, granularity, from, to)));
    }
}

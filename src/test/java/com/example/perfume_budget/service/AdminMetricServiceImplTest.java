package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.coupon.response.CouponMetricResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.dto.customer.CustomerFullDetailsResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.projection.*;
import com.example.perfume_budget.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMetricServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private SiteVisitRepository siteVisitRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminMetricServiceImpl adminMetricService;

    @Mock
    private com.example.perfume_budget.repository.CustomerMetricsQueryRepository customerMetricsQueryRepository;

    @Test
    void getCustomers_Success() {
        CustomerOrderSummary summary = mock(CustomerOrderSummary.class);
        when(summary.getFullName()).thenReturn("John Doe");
        when(summary.getTotalAmountSpent()).thenReturn(new BigDecimal("100.00"));
        when(summary.getCreatedAt()).thenReturn(java.time.LocalDateTime.now());
        when(summary.getLastOrderAt()).thenReturn(java.time.LocalDateTime.of(2026, 3, 4, 10, 0));
        Page<CustomerOrderSummary> page = new PageImpl<>(List.of(summary));
        when(customerMetricsQueryRepository.findCustomers(any(), any(), any(), any(Pageable.class))).thenReturn(page);

        PageResponse<CustomerDataResponse> result = adminMetricService.getCustomers(null, null, org.springframework.data.domain.Sort.Direction.DESC, Pageable.unpaged());

        assertNotNull(result);
        assertEquals(1, result.content().size());
        assertEquals(java.time.LocalDate.of(2026, 3, 4), result.content().get(0).lastOrderDate());
    }

    @Test
    void getCustomers_capsPageSizeTrimsSearchAndDefaultsToOrdersDesc() {
        when(customerMetricsQueryRepository.findCustomers(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        adminMetricService.getCustomers("  jane  ", null, null, org.springframework.data.domain.PageRequest.of(2, 100000));

        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(customerMetricsQueryRepository).findCustomers(
                org.mockito.ArgumentMatchers.eq("jane"),
                org.mockito.ArgumentMatchers.eq(com.example.perfume_budget.enums.CustomerSortField.ORDERS),
                org.mockito.ArgumentMatchers.eq(org.springframework.data.domain.Sort.Direction.DESC), pageable.capture());
        assertEquals(50, pageable.getValue().getPageSize());
        assertEquals(2, pageable.getValue().getPageNumber());
    }

    @Test
    void getCustomers_unpagedUsesDefaultSize() {
        when(customerMetricsQueryRepository.findCustomers(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        adminMetricService.getCustomers(null, "lastOrder", org.springframework.data.domain.Sort.Direction.ASC, Pageable.unpaged());
        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(customerMetricsQueryRepository).findCustomers(any(),
                org.mockito.ArgumentMatchers.eq(com.example.perfume_budget.enums.CustomerSortField.LAST_ORDER),
                org.mockito.ArgumentMatchers.eq(org.springframework.data.domain.Sort.Direction.ASC), pageable.capture());
        assertEquals(20, pageable.getValue().getPageSize());
    }

    @Test
    void getCustomers_rejectsOverlongSearchAndUnknownSort() {
        String tooLong = "a".repeat(101);
        assertThrows(com.example.perfume_budget.exception.BadRequestException.class,
                () -> adminMetricService.getCustomers(tooLong, null, null, Pageable.unpaged()));
        assertThrows(com.example.perfume_budget.exception.BadRequestException.class,
                () -> adminMetricService.getCustomers(null, "email; DROP TABLE users", null, Pageable.unpaged()));
        // exactly the cap is fine
        when(customerMetricsQueryRepository.findCustomers(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        assertNotNull(adminMetricService.getCustomers("a".repeat(100), null, null, Pageable.unpaged()));
    }

    @Test
    void getProductMetrics_usesTheTopProductDefinitions() {
        TopProduct p = mock(TopProduct.class);
        when(p.getId()).thenReturn(7L);
        when(p.getProductName()).thenReturn("Oud");
        when(p.getViewCount()).thenReturn(200L);
        when(p.getAddToCartCount()).thenReturn(8L);
        when(p.getSoldCount()).thenReturn(2L);
        when(productRepository.findEngagementById(7L)).thenReturn(java.util.Optional.of(p));

        MostPurchaseProductResponse r = adminMetricService.getProductMetrics(7L, null, null);

        assertEquals(200L, r.viewCount());
        assertEquals(8L, r.addToCartCount());
        assertEquals(2L, r.soldCount());
        assertEquals(new BigDecimal("25.00"), r.conversionRate());
    }

    @Test
    void getProductMetrics_unknownProductIs404AndBadRangeIs400() {
        when(productRepository.findEngagementById(99L)).thenReturn(java.util.Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> adminMetricService.getProductMetrics(99L, null, null));
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertThrows(com.example.perfume_budget.exception.BadRequestException.class, () -> adminMetricService.getProductMetrics(1L, d, null));
        assertThrows(com.example.perfume_budget.exception.BadRequestException.class, () -> adminMetricService.getProductMetrics(1L, d.plusDays(1), d));
    }

    @Test
    void getCouponMetrics_Success() {
        CouponMetrics metrics = mock(CouponMetrics.class);
        when(metrics.getTotalDiscountGiven()).thenReturn(new BigDecimal("50.00"));
        when(metrics.getRevenueGenerated()).thenReturn(new BigDecimal("500.00"));
        when(metrics.getTotalUsages()).thenReturn(5L);

        when(orderRepository.getAllCouponMetrics(PaymentStatus.COMPLETED)).thenReturn(List.of(metrics));
        when(couponRepository.findAll()).thenReturn(Collections.emptyList());

        CouponMetricResponse result = adminMetricService.getCouponMetrics();

        assertNotNull(result);
        assertTrue(result.totalDiscountGiven().contains("50.00"));
        assertEquals(5L, result.totalUsage());
    }

    @Test
    void getMostPurchasedProduct_Success() {
        TopProduct topProduct = mock(TopProduct.class);
        when(topProduct.getProductName()).thenReturn("Product A");
        when(productRepository.findTopSixMostSoldProducts()).thenReturn(List.of(topProduct));

        var result = adminMetricService.getMostPurchasedProduct();

        assertEquals(1, result.size());
        assertEquals("Product A", result.get(0).productName());
    }

    @Test
    void getSiteVisitMetric_Success_NoDates() {
        SiteVisitProjection projection = mock(SiteVisitProjection.class);
        when(projection.getTotalVisits()).thenReturn(100L);
        when(projection.getTotalUniqueVisitors()).thenReturn(50L);
        when(projection.getTotalPagesVisited()).thenReturn(300L);

        when(siteVisitRepository.getSiteVisitMetrics(any(LocalDate.class), any(LocalDate.class))).thenReturn(projection);
        when(siteVisitRepository.findTop5MostVisitedPages(any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(orderRepository.count()).thenReturn(10L);

        SiteVisitMetric result = adminMetricService.getSiteVisitMetric(null, null);

        assertNotNull(result);
        assertEquals(100L, result.totalVisits());
        assertEquals(20.0, result.uniqueVisitToOrderConversionRate().doubleValue());
    }

    @Test
    void getDashboardMetrics_Success() {
        when(userRepository.countByRolesNot(UserRole.ADMIN)).thenReturn(10L);
        when(orderRepository.count()).thenReturn(20L);
        when(orderRepository.countOrdersByStatus(OrderProcessingStatus.DELIVERED)).thenReturn(15L);
        when(orderRepository.getTotalRevenue(PaymentStatus.COMPLETED)).thenReturn(new BigDecimal("1000.00"));
        when(productRepository.count()).thenReturn(50L);
        when(siteVisitRepository.countAllTimeUniqueVisitors()).thenReturn(100L);
        when(orderItemRepository.findTopProductsByRevenue(PaymentStatus.COMPLETED)).thenReturn(Collections.emptyList());
        when(orderRepository.getDailyRevenue(eq(PaymentStatus.COMPLETED), any(), any())).thenReturn(Collections.emptyList());

        DashboardMetrics result = adminMetricService.getDashboardMetrics();

        assertNotNull(result);
        assertEquals(10, result.totalCustomers());
        assertTrue(result.totalRevenue().contains("1000.00"));
    }

    @Test
    void getCustomerDetails_Success() {
        Long userId = 1L;
        User user = new User();
        user.setId(userId);
        user.setFullName("John Doe");
        user.setEmail("john@example.com");
        user.setDeliveryAddresses(new ArrayList<>());

        Order order = new Order();
        order.setId(101L);
        order.setUser(user);
        order.setTotalAmount(new Money(new BigDecimal("200.00"), CurrencyCode.GHS));
        order.setSubtotal(new Money(new BigDecimal("200.00"), CurrencyCode.GHS));
        order.setStatus(PaymentStatus.COMPLETED);
        order.setItems(new ArrayList<>());
        order.setTaxes(new ArrayList<>());

        when(userRepository.findWithDeliveryAddressesById(userId)).thenReturn(Optional.of(user));
        when(orderRepository.findAllByUser(user)).thenReturn(List.of(order));

        CustomerFullDetailsResponse result = adminMetricService.getCustomerDetails(userId);

        assertNotNull(result);
        assertEquals("John Doe", result.fullName());
        assertTrue(result.totalSpent().contains("200.00"));
        assertEquals(1, result.orderCount());
    }

    @Test
    void getCustomerDetails_UserNotFound() {
        Long userId = 1L;
        when(userRepository.findWithDeliveryAddressesById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> adminMetricService.getCustomerDetails(userId));
    }

    @Test
    void getCustomerOrders_Success() {
        Long userId = 1L;
        User user = new User();
        user.setId(userId);

        Order order = new Order();
        order.setId(101L);
        order.setUser(user);
        order.setTotalAmount(new Money(new BigDecimal("200.00"), CurrencyCode.GHS));
        order.setSubtotal(new Money(new BigDecimal("200.00"), CurrencyCode.GHS));
        order.setStatus(PaymentStatus.COMPLETED);
        order.setItems(new ArrayList<>());
        order.setTaxes(new ArrayList<>());

        Page<Order> page = new PageImpl<>(List.of(order));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(orderRepository.findAllByUser(any(Pageable.class), eq(user))).thenReturn(page);

        PageResponse<OrderListResponse> result = adminMetricService.getCustomerOrders(userId, Pageable.unpaged());

        assertNotNull(result);
        assertEquals(1, result.content().size());
        assertEquals(101L, result.content().get(0).orderId());
    }
}

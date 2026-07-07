package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.analytics.*;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.projection.*;
import com.example.perfume_budget.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesAnalyticsServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CouponRepository couponRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SiteVisitRepository siteVisitRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private WalkInOrderRepository walkInOrderRepository;

    @InjectMocks
    private AdminMetricServiceImpl service;

    // --- helpers to build mock projections ---

    private RevenueAndCOGSBucketProjection revCogsBucket(LocalDateTime ts, double rev, double cogs) {
        RevenueAndCOGSBucketProjection p = mock(RevenueAndCOGSBucketProjection.class);
        when(p.getBucketStart()).thenReturn(ts);
        when(p.getRevenue()).thenReturn(BigDecimal.valueOf(rev));
        when(p.getCogs()).thenReturn(BigDecimal.valueOf(cogs));
        return p;
    }

    private ExpenseBucketProjection expBucket(LocalDateTime ts, double expenses) {
        ExpenseBucketProjection p = mock(ExpenseBucketProjection.class);
        when(p.getBucketStart()).thenReturn(ts);
        when(p.getExpenses()).thenReturn(BigDecimal.valueOf(expenses));
        return p;
    }

    private OnlineOrderStatusProjection onlineStatus(OrderProcessingStatus status, long count) {
        OnlineOrderStatusProjection p = mock(OnlineOrderStatusProjection.class);
        when(p.getDeliveryStatus()).thenReturn(status);
        when(p.getCount()).thenReturn(count);
        return p;
    }

    private WalkInOrderStatusProjection walkInStatus(WalkInOrderStatus status, long count) {
        WalkInOrderStatusProjection p = mock(WalkInOrderStatusProjection.class);
        when(p.getStatus()).thenReturn(status);
        when(p.getCount()).thenReturn(count);
        return p;
    }

    private void stubAllReposEmpty() {
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());
    }

    // ----- source filter tests -----

    @Test
    void source_ONLINE_passesOnlySaleType() {
        stubAllReposEmpty();
        service.getSalesAnalytics(SalesAnalyticsSource.ONLINE, SalesAnalyticsGranularity.DAY, null, null);
        verify(journalEntryRepository, atLeastOnce()).getRevenueAndCOGSByBucket(
                eq(List.of("SALE")), any(), any(), anyString());
    }

    @Test
    void source_WALK_IN_passesOnlyWalkInSaleType() {
        stubAllReposEmpty();
        service.getSalesAnalytics(SalesAnalyticsSource.WALK_IN, SalesAnalyticsGranularity.DAY, null, null);
        verify(journalEntryRepository, atLeastOnce()).getRevenueAndCOGSByBucket(
                eq(List.of("WALK_IN_SALE")), any(), any(), anyString());
    }

    @Test
    void source_ALL_passesBothTypes() {
        stubAllReposEmpty();
        service.getSalesAnalytics(SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        verify(journalEntryRepository, atLeastOnce()).getRevenueAndCOGSByBucket(
                eq(List.of("SALE", "WALK_IN_SALE")), any(), any(), anyString());
    }

    // ----- granularity bucket label tests -----

    @Test
    void granularity_DAY_producesSevenBucketsWithDayLabels() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        assertEquals(7, r.revenueBreakdown().size());
        r.revenueBreakdown().forEach(p ->
                assertFalse(p.name().isBlank()));
    }

    @Test
    void granularity_WEEK_producesEightBuckets() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.WEEK, null, null);
        assertEquals(8, r.revenueBreakdown().size());
    }

    @Test
    void granularity_MONTH_producesTwelveBuckets() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.MONTH, null, null);
        assertEquals(12, r.revenueBreakdown().size());
    }

    @Test
    void granularity_YEAR_producesFiveBuckets() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.YEAR, null, null);
        assertEquals(5, r.revenueBreakdown().size());
    }

    // ----- zero-fill test -----

    @Test
    void zeroFill_missingBucketsGetZeroRevenue() {
        LocalDateTime dayBucket = LocalDate.now().minusDays(3).atStartOfDay();
        RevenueAndCOGSBucketProjection rcBucket = revCogsBucket(dayBucket, 200.0, 80.0);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(rcBucket));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        assertEquals(7, r.revenueBreakdown().size());
        long nonZeroBuckets = r.revenueBreakdown().stream()
                .filter(p -> p.revenue().compareTo(BigDecimal.ZERO) > 0).count();
        assertEquals(1, nonZeroBuckets);
    }

    // ----- profit calculation tests -----

    @Test
    void profitBreakdown_grossAndNetCalculatedPerBucket() {
        LocalDateTime dayBucket = LocalDate.now().minusDays(1).atStartOfDay();
        RevenueAndCOGSBucketProjection rcBucket = revCogsBucket(dayBucket, 300.0, 100.0);
        ExpenseBucketProjection eBucket = expBucket(dayBucket, 50.0);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(rcBucket));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(eBucket));
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        ProfitDataPointResponse bucket = r.profitBreakdown().stream()
                .filter(p -> p.grossProfit().compareTo(BigDecimal.ZERO) != 0)
                .findFirst().orElseThrow();
        assertEquals(0, bucket.grossProfit().compareTo(BigDecimal.valueOf(200.0)));
        assertEquals(0, bucket.netProfit().compareTo(BigDecimal.valueOf(150.0)));
    }

    @Test
    void netProfit_canBeNegative() {
        LocalDateTime dayBucket = LocalDate.now().minusDays(1).atStartOfDay();
        RevenueAndCOGSBucketProjection rcBucket = revCogsBucket(dayBucket, 100.0, 50.0);
        ExpenseBucketProjection eBucket = expBucket(dayBucket, 200.0);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(rcBucket));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(eBucket));
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        ProfitDataPointResponse bucket = r.profitBreakdown().stream()
                .filter(p -> p.netProfit().compareTo(BigDecimal.ZERO) < 0)
                .findFirst().orElseThrow(() -> new AssertionError("Expected a negative net profit bucket"));
        assertTrue(bucket.netProfit().compareTo(BigDecimal.ZERO) < 0);
    }

    // ----- trend tests -----

    @Test
    void trend_positiveIncrease_isUpTrueAndPercent() {
        LocalDateTime curBucket = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime prevBucket = LocalDate.now().minusDays(8).atStartOfDay();
        RevenueAndCOGSBucketProjection curRc = revCogsBucket(curBucket, 120.0, 0.0);
        RevenueAndCOGSBucketProjection prevRc = revCogsBucket(prevBucket, 100.0, 0.0);

        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(curRc))
                .thenReturn(List.of(prevRc));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        MiniStatResponse revStat = r.miniStats().stream()
                .filter(s -> s.label().equals("Total Revenue")).findFirst().orElseThrow();
        assertTrue(revStat.isUp());
        assertEquals("20%", revStat.trend());
    }

    @Test
    void trend_decrease_isUpFalse() {
        LocalDateTime curBucket = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime prevBucket = LocalDate.now().minusDays(8).atStartOfDay();
        RevenueAndCOGSBucketProjection curRc = revCogsBucket(curBucket, 80.0, 0.0);
        RevenueAndCOGSBucketProjection prevRc = revCogsBucket(prevBucket, 100.0, 0.0);

        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(curRc))
                .thenReturn(List.of(prevRc));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        MiniStatResponse revStat = r.miniStats().stream()
                .filter(s -> s.label().equals("Total Revenue")).findFirst().orElseThrow();
        assertFalse(revStat.isUp());
        assertEquals("20%", revStat.trend());
    }

    @Test
    void trend_previousZeroCurrentZero_zeroPercentIsUp() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        MiniStatResponse revStat = r.miniStats().stream()
                .filter(s -> s.label().equals("Total Revenue")).findFirst().orElseThrow();
        assertEquals("0%", revStat.trend());
        assertTrue(revStat.isUp());
    }

    @Test
    void trend_previousZeroCurrentPositive_hundredPercentIsUp() {
        LocalDateTime curBucket = LocalDate.now().minusDays(1).atStartOfDay();
        RevenueAndCOGSBucketProjection curRc = revCogsBucket(curBucket, 50.0, 0.0);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(curRc))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any())).thenReturn(List.of());
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any())).thenReturn(List.of());

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        MiniStatResponse revStat = r.miniStats().stream()
                .filter(s -> s.label().equals("Total Revenue")).findFirst().orElseThrow();
        assertEquals("100%", revStat.trend());
        assertTrue(revStat.isUp());
    }

    // ----- miniStats structure test -----

    @Test
    void miniStats_alwaysFiveCards() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        assertEquals(5, r.miniStats().size());
        List<String> labels = r.miniStats().stream().map(MiniStatResponse::label).toList();
        assertTrue(labels.contains("Avg Order Value"));
        assertTrue(labels.contains("Total Revenue"));
        assertTrue(labels.contains("Total Orders"));
        assertTrue(labels.contains("Total Gross Profit"));
        assertTrue(labels.contains("Total Net Profit"));
    }

    // ----- order status tests -----

    @Test
    void orderStatus_ONLINE_returnsOnlyOnlineLabels() {
        OnlineOrderStatusProjection delivered = onlineStatus(OrderProcessingStatus.DELIVERED, 5);
        OnlineOrderStatusProjection pending = onlineStatus(OrderProcessingStatus.PENDING, 2);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any()))
                .thenReturn(List.of(delivered, pending));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ONLINE, SalesAnalyticsGranularity.DAY, null, null);

        List<String> names = r.orderStatus().stream().map(OrderStatusSliceResponse::name).toList();
        assertTrue(names.contains("Delivered"));
        assertTrue(names.contains("Pending"));
        assertFalse(names.contains("Completed"));
    }

    @Test
    void orderStatus_WALK_IN_returnsOnlyWalkInLabels() {
        WalkInOrderStatusProjection completed = walkInStatus(WalkInOrderStatus.COMPLETED, 10);
        WalkInOrderStatusProjection cancelled = walkInStatus(WalkInOrderStatus.CANCELLED, 1);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any()))
                .thenReturn(List.of(completed, cancelled));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.WALK_IN, SalesAnalyticsGranularity.DAY, null, null);

        List<String> names = r.orderStatus().stream().map(OrderStatusSliceResponse::name).toList();
        assertTrue(names.contains("Completed"));
        assertTrue(names.contains("Cancelled"));
        assertFalse(names.contains("Delivered"));
    }

    @Test
    void orderStatus_ALL_combinesBothSourcesNormalized() {
        OnlineOrderStatusProjection onlineCancelled = onlineStatus(OrderProcessingStatus.CANCELLED, 3);
        WalkInOrderStatusProjection walkInCancelled = walkInStatus(WalkInOrderStatus.CANCELLED, 2);
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any()))
                .thenReturn(List.of(onlineCancelled));
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any()))
                .thenReturn(List.of(walkInCancelled));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        OrderStatusSliceResponse cancelled = r.orderStatus().stream()
                .filter(s -> s.name().equals("Cancelled")).findFirst().orElseThrow();
        assertEquals(5L, cancelled.value());
    }

    // ----- profitBreakdown and revenueBreakdown aligned -----

    @Test
    void profitBreakdown_alignedWithRevenueBreakdown() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        assertEquals(r.revenueBreakdown().size(), r.profitBreakdown().size());
        for (int i = 0; i < r.revenueBreakdown().size(); i++) {
            assertEquals(r.revenueBreakdown().get(i).name(), r.profitBreakdown().get(i).name());
        }
    }
}

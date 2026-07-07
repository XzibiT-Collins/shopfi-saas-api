package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.admin_dashboard.OrderCountMetric;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.dto.coupon.response.CouponMetricResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.customer.CustomerFullDetailsResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.dto.analytics.MiniStatResponse;
import com.example.perfume_budget.dto.analytics.OrderStatusSliceResponse;
import com.example.perfume_budget.dto.analytics.ProfitDataPointResponse;
import com.example.perfume_budget.dto.analytics.RevenueDataPointResponse;
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.*;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.projection.*;
import com.example.perfume_budget.projection.ExpenseBucketProjection;
import com.example.perfume_budget.projection.OnlineOrderStatusProjection;
import com.example.perfume_budget.projection.RevenueAndCOGSBucketProjection;
import com.example.perfume_budget.projection.WalkInOrderStatusProjection;
import com.example.perfume_budget.repository.*;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.WalkInOrderRepository;
import com.example.perfume_budget.service.interfaces.AdminMetricService;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminMetricServiceImpl implements AdminMetricService {
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final ProductRepository productRepository;
    private final SiteVisitRepository siteVisitRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final WalkInOrderRepository walkInOrderRepository;

    @Override
    public PageResponse<CustomerDataResponse> getCustomers(Pageable pageable) {
        Page<CustomerOrderSummary> summaryPage = orderRepository.getCustomerOrderSummary(pageable);
        Page<CustomerDataResponse> customerDataResponses = summaryPage.map(CustomerDataMapper::toCustomerDataResponse);
        return PaginationUtil.createPageResponse(customerDataResponses);
    }

    @Override
    public PageResponse<CustomerDataResponse> searchCustomer(Pageable pageable, String keyword) {
        return null;
    }

    @Override
    public CouponMetricResponse getCouponMetrics() {
        List<CouponMetrics> metrics = orderRepository.getAllCouponMetrics(PaymentStatus.COMPLETED);
        List<CouponListResponse> coupons = couponRepository.findAll()
                .stream().map(CouponMapper::toCouponListResponse)
                .toList();

        Map<String, Object> summary = Map.of(
                "totalDiscountGiven", metrics.stream()
                        .map(CouponMetrics::getTotalDiscountGiven)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                "totalRevenueGenerated", metrics.stream()
                        .map(CouponMetrics::getRevenueGenerated)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                "totalUsages", metrics.stream()
                        .mapToLong(CouponMetrics::getTotalUsages)
                        .sum()
        );

        return CouponMetricResponse.builder()
                .coupons(coupons)
                .totalCreated(coupons.size())
                .totalUsage((Long) summary.get("totalUsages"))
                .totalDiscountGiven(new Money(
                        ((BigDecimal) summary.get("totalDiscountGiven")), CurrencyCode.GHS
                ).toString())
                .totalRevenueGenerated(new Money(
                        ((BigDecimal) summary.get("totalRevenueGenerated")), CurrencyCode.GHS
                ).toString())
                .build();
    }

    @Override
    public List<MostPurchaseProductResponse> getMostPurchasedProduct() {
        return productRepository
                .findTopSixMostSoldProducts()
                .stream()
                .map(TopProductMapper::toMostPurchasedProduct)
                .toList();
    }

    @Override
    public SiteVisitMetric getSiteVisitMetric(@Nullable LocalDate from, @Nullable LocalDate to) {
        SiteVisitProjection siteVisitProjection;
        BigDecimal visitToOrderConversionRate;
        List<PageVisitMetric> top5MostVisitedPages;

        if(from == null && to == null){
            siteVisitProjection = getSiteVisitProjection(null, null);
            top5MostVisitedPages = getTop5MostVisitedPages(null,null);
        }else{
            siteVisitProjection = getSiteVisitProjection(from, to);
            top5MostVisitedPages = getTop5MostVisitedPages(from,to);
        }
        visitToOrderConversionRate = calculateVisitToOrderConversionRate(
                siteVisitProjection.getTotalUniqueVisitors()
        );

        return SiteVisitMetric.builder()
                .totalVisits(siteVisitProjection.getTotalVisits())
                .totalUniqueVisitors(siteVisitProjection.getTotalUniqueVisitors())
                .totalPageViews(siteVisitProjection.getTotalPagesVisited())
                .uniqueVisitToOrderConversionRate(visitToOrderConversionRate)
                .top5MostVisitedPages(top5MostVisitedPages)
                .build();
    }

    @Override
    public DashboardMetrics getDashboardMetrics() {
        Long totalCustomers = getTotalCustomerCount();
        OrderCountMetric orderCountMetric = getOrderCountMetric();
        Long totalProducts = getTotalProductsCount();
        Long totalSiteVisitsCount = getTotalSiteVisitsCount();
        BigDecimal allTotalRevenue = getAllTimeRevenue();
        List<TopCompositionMetric> top5Compositions = getTop5Compositions();
        List<DailyRevenueMetric> dailyRevenueMetrics = getDailyRevenueMetrics();

        return DashboardMetrics.builder()
                .totalCustomers(totalCustomers)
                .orderCountMetric(orderCountMetric)
                .totalProducts(totalProducts)
                .totalSiteVisits(totalSiteVisitsCount)
                .totalRevenue(new Money(allTotalRevenue, CurrencyCode.GHS).toString())
                .top5Compositions(top5Compositions)
                .dailyRevenueMetric(dailyRevenueMetrics)
                .build();
    }

    @Override
    public List<LowStockProduct> getLowStockProducts() {
        Pageable pageable = Pageable.ofSize(10);
        return productRepository.findLowStockProducts(pageable);
    }

    @Override
    public List<TopCustomer> getTopCustomers() {
        return orderRepository.findTopCustomers(PaymentStatus.COMPLETED, Pageable.ofSize(10));
    }

    @Override
    public CustomerFullDetailsResponse getCustomerDetails(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Order> orders = orderRepository.findAllByUser(user);
        
        BigDecimal totalSpent = orders.stream()
                .filter(o -> o.getStatus() == PaymentStatus.COMPLETED)
                .map(o -> o.getTotalAmount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return CustomerFullDetailsResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .dateJoined(user.getCreatedAt())
                .isActive(user.isActive())
                .addresses(user.getDeliveryAddresses().stream()
                        .map(DeliveryDetailMapper::toDeliveryDetailResponse)
                        .toList())
                .totalSpent(new Money(totalSpent, CurrencyCode.GHS).toString())
                .orderCount((long) orders.size())
                .build();
    }

    @Override
    public PageResponse<OrderListResponse> getCustomerOrders(Long userId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Page<Order> orderPage = orderRepository.findAllByUser(pageable, user);
        Page<OrderListResponse> orderResponsePage = orderPage.map(OrderMapper::toOrderListResponse);
        
        return PaginationUtil.createPageResponse(orderResponsePage);
    }

    private Long getTotalCustomerCount() {
        return userRepository.countByRolesNot(UserRole.ADMIN);
    }

    private OrderCountMetric getOrderCountMetric() {
        Long totalOrders = orderRepository.count();
        Long totalDeliveredOrders = orderRepository.countOrdersByStatus(OrderProcessingStatus.DELIVERED);
        Long totalPendingOrders = orderRepository.countOrdersByStatus(OrderProcessingStatus.PENDING);
        Long totalCancelledOrders = orderRepository.countOrdersByStatus(OrderProcessingStatus.CANCELLED);
        
        return OrderCountMetric.builder()
                .totalOrders(totalOrders)
                .totalDeliveredOrders(totalDeliveredOrders)
                .totalPendingOrders(totalPendingOrders)
                .totalCancelledOrders(totalCancelledOrders)
                .build();
    }


    private Long getTotalProductsCount() {
        return productRepository.count();
    }

    private Long getTotalSiteVisitsCount() {
        return siteVisitRepository.countAllTimeUniqueVisitors();
    }

    private BigDecimal getAllTimeRevenue() {
        return orderRepository.getTotalRevenue(PaymentStatus.COMPLETED);
    }

    private List<TopCompositionMetric> getTop5Compositions() {
        return orderItemRepository.findTopProductsByRevenue(PaymentStatus.COMPLETED);
    }

    private List<DailyRevenueMetric> getDailyRevenueMetrics() {
        LocalDateTime start = LocalDate.now().minusDays(6).atStartOfDay();
        LocalDateTime end = LocalDate.now().atTime(23, 59, 59);

        return orderRepository.getDailyRevenue(PaymentStatus.COMPLETED, start, end);
    }

    private List<PageVisitMetric> getTop5MostVisitedPages(@Nullable LocalDate from, @Nullable LocalDate to) {
        if(from == null && to == null){
            return siteVisitRepository
                    .findTop5MostVisitedPages(LocalDate.of(2020,1,1), LocalDate.now());
        }else{
            return siteVisitRepository.findTop5MostVisitedPages(from, to);
        }
    }

    private BigDecimal calculateVisitToOrderConversionRate(Long totalUniqueVisitors) {
        long totalOrderPlaced = orderRepository.count();
        return totalUniqueVisitors > 0
                ? BigDecimal.valueOf((double) totalOrderPlaced / totalUniqueVisitors * 100)
                : BigDecimal.ZERO;
    }

    private SiteVisitProjection getSiteVisitProjection(@Nullable LocalDate from, @Nullable LocalDate to) {
        if(from == null && to == null){
            return siteVisitRepository
                    .getSiteVisitMetrics(LocalDate.of(2020,1,1), LocalDate.now());
        }else{
            return siteVisitRepository.getSiteVisitMetrics(from, to);
        }
    }

    // -------------------------------------------------------------------------
    // Analytics window record + constants
    // -------------------------------------------------------------------------

    private record ResolvedAnalyticsWindow(
            LocalDateTime currentStart,
            LocalDateTime currentEnd,
            LocalDateTime previousStart,
            LocalDateTime previousEnd
    ) {}

    private static final List<String> EXPENSE_CATEGORIES = List.of(
            "DISCOUNT_EXPENSE", "COUPON_EXPENSE", "MARKETING_EXPENSE",
            "LOGISTICS_EXPENSE", "MISCELLANEOUS_EXPENSE", "GENERAL_EXPENSE"
    );

    // -------------------------------------------------------------------------
    // getSalesAnalytics – main entry point
    // -------------------------------------------------------------------------

    @Override
    public SalesAnalyticsResponse getSalesAnalytics(
            SalesAnalyticsSource source,
            SalesAnalyticsGranularity granularity,
            LocalDate from,
            LocalDate to) {

        if (source == null)      source = SalesAnalyticsSource.ALL;
        if (granularity == null) granularity = SalesAnalyticsGranularity.DAY;

        ResolvedAnalyticsWindow window = resolveWindow(granularity, from, to);
        List<String> journalTypes = journalEntryTypesFor(source);
        String gran = granularity.name().toLowerCase();

        // Query A — revenue + COGS
        List<RevenueAndCOGSBucketProjection> currentRevCogs =
                journalEntryRepository.getRevenueAndCOGSByBucket(
                        journalTypes, window.currentStart(), window.currentEnd(), gran);
        List<RevenueAndCOGSBucketProjection> previousRevCogs =
                journalEntryRepository.getRevenueAndCOGSByBucket(
                        journalTypes, window.previousStart(), window.previousEnd(), gran);

        // Query B — operating expenses (always ALL)
        List<ExpenseBucketProjection> currentExpenses =
                journalEntryRepository.getExpensesByBucket(
                        EXPENSE_CATEGORIES, window.currentStart(), window.currentEnd(), gran);
        List<ExpenseBucketProjection> previousExpenses =
                journalEntryRepository.getExpensesByBucket(
                        EXPENSE_CATEGORIES, window.previousStart(), window.previousEnd(), gran);

        // Order counts
        boolean includeOnline  = source != SalesAnalyticsSource.WALK_IN;
        boolean includeWalkIn  = source != SalesAnalyticsSource.ONLINE;

        long currentOnlineOrders  = includeOnline ? orderRepository.countCompletedOnlineOrders(window.currentStart(),  window.currentEnd())  : 0L;
        long previousOnlineOrders = includeOnline ? orderRepository.countCompletedOnlineOrders(window.previousStart(), window.previousEnd()) : 0L;
        long currentWalkInOrders  = includeWalkIn ? walkInOrderRepository.countCompletedWalkInOrders(window.currentStart(),  window.currentEnd())  : 0L;
        long previousWalkInOrders = includeWalkIn ? walkInOrderRepository.countCompletedWalkInOrders(window.previousStart(), window.previousEnd()) : 0L;

        // Order status
        List<OnlineOrderStatusProjection> onlineStatuses = includeOnline
                ? orderRepository.countOnlineOrdersByDeliveryStatus(window.currentStart(), window.currentEnd())
                : List.of();
        List<WalkInOrderStatusProjection> walkInStatuses = includeWalkIn
                ? walkInOrderRepository.countWalkInOrdersByStatus(window.currentStart(), window.currentEnd())
                : List.of();

        // Buckets
        List<LocalDateTime> buckets = generateBuckets(granularity, window.currentStart(), window.currentEnd());

        // Aggregated totals
        BigDecimal currentRevenue   = sumRevenue(currentRevCogs);
        BigDecimal previousRevenue  = sumRevenue(previousRevCogs);
        BigDecimal currentCogs      = sumCogs(currentRevCogs);
        BigDecimal previousCogs     = sumCogs(previousRevCogs);
        BigDecimal currentExpTotal  = sumExpenses(currentExpenses);
        BigDecimal previousExpTotal = sumExpenses(previousExpenses);

        return new SalesAnalyticsResponse(
                buildRevenueBreakdown(currentRevCogs, buckets, granularity),
                buildProfitBreakdown(currentRevCogs, currentExpenses, buckets, granularity),
                buildMiniStats(currentRevenue, previousRevenue, currentCogs, previousCogs,
                        currentExpTotal, previousExpTotal,
                        currentOnlineOrders, previousOnlineOrders,
                        currentWalkInOrders, previousWalkInOrders, source),
                buildOrderStatus(source, onlineStatuses, walkInStatuses)
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ResolvedAnalyticsWindow resolveWindow(SalesAnalyticsGranularity granularity,
                                                   LocalDate from, LocalDate to) {
        LocalDateTime currentStart;
        LocalDateTime currentEnd;
        LocalDate today = LocalDate.now();

        if (from != null) {
            currentStart = from.atStartOfDay();
            currentEnd = to.atTime(23, 59, 59);
        } else {
            currentEnd = today.atTime(23, 59, 59);
            currentStart = switch (granularity) {
                case DAY   -> today.minusDays(6).atStartOfDay();
                case WEEK  -> today.with(DayOfWeek.MONDAY).minusWeeks(7).atStartOfDay();
                case MONTH -> today.withDayOfMonth(1).minusMonths(11).atStartOfDay();
                case YEAR  -> today.withDayOfYear(1).minusYears(4).atStartOfDay();
            };
        }

        long durationSeconds = ChronoUnit.SECONDS.between(currentStart, currentEnd);
        LocalDateTime previousEnd = currentStart.minusSeconds(1);
        LocalDateTime previousStart = previousEnd.minusSeconds(durationSeconds);

        return new ResolvedAnalyticsWindow(currentStart, currentEnd, previousStart, previousEnd);
    }

    private List<String> journalEntryTypesFor(SalesAnalyticsSource source) {
        return switch (source) {
            case ONLINE  -> List.of("SALE");
            case WALK_IN -> List.of("WALK_IN_SALE");
            case ALL     -> List.of("SALE", "WALK_IN_SALE");
        };
    }

    private LocalDateTime truncateToBucket(SalesAnalyticsGranularity granularity, LocalDateTime dt) {
        return switch (granularity) {
            case DAY   -> dt.truncatedTo(ChronoUnit.DAYS);
            case WEEK  -> dt.with(DayOfWeek.MONDAY).truncatedTo(ChronoUnit.DAYS);
            case MONTH -> dt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case YEAR  -> dt.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
        };
    }

    private LocalDateTime advanceBucket(SalesAnalyticsGranularity granularity, LocalDateTime dt) {
        return switch (granularity) {
            case DAY   -> dt.plusDays(1);
            case WEEK  -> dt.plusWeeks(1);
            case MONTH -> dt.plusMonths(1);
            case YEAR  -> dt.plusYears(1);
        };
    }

    private List<LocalDateTime> generateBuckets(SalesAnalyticsGranularity granularity,
                                                 LocalDateTime start, LocalDateTime end) {
        List<LocalDateTime> buckets = new ArrayList<>();
        LocalDateTime current = truncateToBucket(granularity, start);
        LocalDateTime last = truncateToBucket(granularity, end);
        while (!current.isAfter(last)) {
            buckets.add(current);
            current = advanceBucket(granularity, current);
        }
        return buckets;
    }

    private String labelForBucket(SalesAnalyticsGranularity granularity, LocalDateTime bucket) {
        return switch (granularity) {
            case DAY   -> bucket.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            case WEEK  -> bucket.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
            case MONTH -> bucket.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            case YEAR  -> String.valueOf(bucket.getYear());
        };
    }

    private BigDecimal calculateTrendPercent(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) > 0) {
            return current.subtract(previous)
                    .divide(previous, 4, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .abs();
        }
        return current.compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.valueOf(100)
                : BigDecimal.ZERO;
    }

    private String formatTrend(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }

    private BigDecimal sumRevenue(List<RevenueAndCOGSBucketProjection> buckets) {
        return buckets.stream()
                .map(RevenueAndCOGSBucketProjection::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCogs(List<RevenueAndCOGSBucketProjection> buckets) {
        return buckets.stream()
                .map(RevenueAndCOGSBucketProjection::getCogs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumExpenses(List<ExpenseBucketProjection> buckets) {
        return buckets.stream()
                .map(ExpenseBucketProjection::getExpenses)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<RevenueDataPointResponse> buildRevenueBreakdown(
            List<RevenueAndCOGSBucketProjection> dbBuckets,
            List<LocalDateTime> allBuckets,
            SalesAnalyticsGranularity granularity) {

        Map<LocalDateTime, BigDecimal> revenueMap = dbBuckets.stream()
                .collect(Collectors.toMap(
                        RevenueAndCOGSBucketProjection::getBucketStart,
                        RevenueAndCOGSBucketProjection::getRevenue));

        return allBuckets.stream()
                .map(b -> new RevenueDataPointResponse(
                        labelForBucket(granularity, b),
                        revenueMap.getOrDefault(b, BigDecimal.ZERO)))
                .toList();
    }

    private List<ProfitDataPointResponse> buildProfitBreakdown(
            List<RevenueAndCOGSBucketProjection> revCogsBuckets,
            List<ExpenseBucketProjection> expenseBuckets,
            List<LocalDateTime> allBuckets,
            SalesAnalyticsGranularity granularity) {

        Map<LocalDateTime, BigDecimal> revenueMap = revCogsBuckets.stream()
                .collect(Collectors.toMap(
                        RevenueAndCOGSBucketProjection::getBucketStart,
                        RevenueAndCOGSBucketProjection::getRevenue));
        Map<LocalDateTime, BigDecimal> cogsMap = revCogsBuckets.stream()
                .collect(Collectors.toMap(
                        RevenueAndCOGSBucketProjection::getBucketStart,
                        RevenueAndCOGSBucketProjection::getCogs));
        Map<LocalDateTime, BigDecimal> expenseMap = expenseBuckets.stream()
                .collect(Collectors.toMap(
                        ExpenseBucketProjection::getBucketStart,
                        ExpenseBucketProjection::getExpenses));

        return allBuckets.stream()
                .map(b -> {
                    BigDecimal rev  = revenueMap.getOrDefault(b, BigDecimal.ZERO);
                    BigDecimal cogs = cogsMap.getOrDefault(b, BigDecimal.ZERO);
                    BigDecimal exp  = expenseMap.getOrDefault(b, BigDecimal.ZERO);
                    BigDecimal gp   = rev.subtract(cogs);
                    BigDecimal np   = gp.subtract(exp);
                    return new ProfitDataPointResponse(labelForBucket(granularity, b), gp, np);
                })
                .toList();
    }

    private List<MiniStatResponse> buildMiniStats(
            BigDecimal currentRevenue,  BigDecimal previousRevenue,
            BigDecimal currentCogs,     BigDecimal previousCogs,
            BigDecimal currentExpenses, BigDecimal previousExpenses,
            long currentOnlineOrders,   long previousOnlineOrders,
            long currentWalkInOrders,   long previousWalkInOrders,
            SalesAnalyticsSource source) {

        long currentOrders  = ordersForSource(source, currentOnlineOrders, currentWalkInOrders);
        long previousOrders = ordersForSource(source, previousOnlineOrders, previousWalkInOrders);

        BigDecimal currentAOV = currentOrders > 0
                ? currentRevenue.divide(BigDecimal.valueOf(currentOrders), 2, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO;
        BigDecimal previousAOV = previousOrders > 0
                ? previousRevenue.divide(BigDecimal.valueOf(previousOrders), 2, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO;

        BigDecimal currentGP  = currentRevenue.subtract(currentCogs);
        BigDecimal previousGP = previousRevenue.subtract(previousCogs);
        BigDecimal currentNP  = currentGP.subtract(currentExpenses);
        BigDecimal previousNP = previousGP.subtract(previousExpenses);

        return List.of(
                moneyMiniStat("Avg Order Value",    currentAOV,      previousAOV),
                moneyMiniStat("Total Revenue",      currentRevenue,  previousRevenue),
                countMiniStat("Total Orders",       currentOrders,   previousOrders),
                moneyMiniStat("Total Gross Profit", currentGP,       previousGP),
                moneyMiniStat("Total Net Profit",   currentNP,       previousNP)
        );
    }

    private long ordersForSource(SalesAnalyticsSource source, long online, long walkIn) {
        return switch (source) {
            case ONLINE  -> online;
            case WALK_IN -> walkIn;
            case ALL     -> online + walkIn;
        };
    }

    private MiniStatResponse moneyMiniStat(String label, BigDecimal current, BigDecimal previous) {
        BigDecimal trend = calculateTrendPercent(current, previous);
        boolean isUp = current.compareTo(previous) >= 0;
        return new MiniStatResponse(label, new Money(current, CurrencyCode.GHS).toString(),
                formatTrend(trend), isUp);
    }

    private MiniStatResponse countMiniStat(String label, long current, long previous) {
        BigDecimal cur  = BigDecimal.valueOf(current);
        BigDecimal prev = BigDecimal.valueOf(previous);
        BigDecimal trend = calculateTrendPercent(cur, prev);
        boolean isUp = current >= previous;
        return new MiniStatResponse(label, String.valueOf(current), formatTrend(trend), isUp);
    }

    private List<OrderStatusSliceResponse> buildOrderStatus(
            SalesAnalyticsSource source,
            List<OnlineOrderStatusProjection> onlineStatuses,
            List<WalkInOrderStatusProjection> walkInStatuses) {
        return switch (source) {
            case ONLINE  -> buildOnlineStatus(onlineStatuses);
            case WALK_IN -> buildWalkInStatus(walkInStatuses);
            case ALL     -> buildCombinedStatus(onlineStatuses, walkInStatuses);
        };
    }

    private List<OrderStatusSliceResponse> buildOnlineStatus(
            List<OnlineOrderStatusProjection> statuses) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("Pending", 0L); map.put("Packing", 0L);
        map.put("Out for Delivery", 0L); map.put("Delivered", 0L); map.put("Cancelled", 0L);
        statuses.forEach(p -> {
            String label = onlineLabel(p.getDeliveryStatus().name());
            if (label != null) map.merge(label, p.getCount(), Long::sum);
        });
        return toSlices(map);
    }

    private List<OrderStatusSliceResponse> buildWalkInStatus(
            List<WalkInOrderStatusProjection> statuses) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("Completed", 0L); map.put("Cancelled", 0L); map.put("Refunded", 0L);
        statuses.forEach(p -> {
            String label = walkInLabel(p.getStatus().name());
            if (label != null) map.merge(label, p.getCount(), Long::sum);
        });
        return toSlices(map);
    }

    private List<OrderStatusSliceResponse> buildCombinedStatus(
            List<OnlineOrderStatusProjection> onlineStatuses,
            List<WalkInOrderStatusProjection> walkInStatuses) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("Completed", 0L); map.put("Pending", 0L); map.put("Packing", 0L);
        map.put("Out for Delivery", 0L); map.put("Delivered", 0L);
        map.put("Cancelled", 0L); map.put("Refunded", 0L);
        onlineStatuses.forEach(p -> {
            String label = onlineLabel(p.getDeliveryStatus().name());
            if (label != null) map.merge(label, p.getCount(), Long::sum);
        });
        walkInStatuses.forEach(p -> {
            String label = walkInLabel(p.getStatus().name());
            if (label != null) map.merge(label, p.getCount(), Long::sum);
        });
        return toSlices(map);
    }

    private String onlineLabel(String name) {
        return switch (name) {
            case "PENDING"          -> "Pending";
            case "PACKING"          -> "Packing";
            case "OUT_FOR_DELIVERY" -> "Out for Delivery";
            case "DELIVERED"        -> "Delivered";
            case "CANCELLED"        -> "Cancelled";
            default                 -> null;
        };
    }

    private String walkInLabel(String name) {
        return switch (name) {
            case "COMPLETED" -> "Completed";
            case "CANCELLED" -> "Cancelled";
            case "REFUNDED"  -> "Refunded";
            default          -> null;
        };
    }

    private List<OrderStatusSliceResponse> toSlices(Map<String, Long> map) {
        return map.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new OrderStatusSliceResponse(e.getKey(), e.getValue()))
                .toList();
    }
}

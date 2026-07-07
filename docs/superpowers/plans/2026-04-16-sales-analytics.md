# Sales Analytics Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/admin/metrics/sales-analytics` returning revenue breakdown, profit breakdown (gross + net per bucket), five miniStat summary cards, and order status distribution — filtered by source and granularity.

**Architecture:** Two native SQL queries on `JournalEntryRepository` aggregate revenue+COGS (source-filtered) and operating expenses (always ALL) into time buckets. Order count and status queries hit `OrderRepository` and `WalkInOrderRepository` via JPQL. The service assembles all results into the response; the controller handles binding and date-range validation.

**Tech Stack:** Spring Boot 3, Spring Data JPA, PostgreSQL (`DATE_TRUNC`), JUnit 5, Mockito, `@WebMvcTest`.

---

## File Map

**New files:**
```
src/main/java/com/example/perfume_budget/
  enums/SalesAnalyticsSource.java
  enums/SalesAnalyticsGranularity.java
  dto/analytics/SalesAnalyticsResponse.java
  dto/analytics/RevenueDataPointResponse.java
  dto/analytics/ProfitDataPointResponse.java
  dto/analytics/MiniStatResponse.java
  dto/analytics/OrderStatusSliceResponse.java
  projection/RevenueAndCOGSBucketProjection.java
  projection/ExpenseBucketProjection.java
  projection/OnlineOrderStatusProjection.java
  projection/WalkInOrderStatusProjection.java

src/test/java/com/example/perfume_budget/
  service/SalesAnalyticsServiceTest.java
  controller/SalesAnalyticsControllerTest.java
```

**Modified files:**
```
src/main/java/com/example/perfume_budget/
  repository/JournalEntryRepository.java       — Query A (revenue+COGS) + Query B (expenses)
  repository/OrderRepository.java              — count + status queries
  repository/WalkInOrderRepository.java        — count + status queries
  service/interfaces/AdminMetricService.java   — add getSalesAnalytics()
  service/AdminMetricServiceImpl.java          — full implementation + new repo injections
  controller/AdminMetricController.java        — new endpoint
```

---

### Task 1: Create enums

**Files:**
- Create: `src/main/java/com/example/perfume_budget/enums/SalesAnalyticsSource.java`
- Create: `src/main/java/com/example/perfume_budget/enums/SalesAnalyticsGranularity.java`

- [ ] **Step 1: Create SalesAnalyticsSource**

```java
package com.example.perfume_budget.enums;

public enum SalesAnalyticsSource {
    ALL,
    ONLINE,
    WALK_IN
}
```

- [ ] **Step 2: Create SalesAnalyticsGranularity**

```java
package com.example.perfume_budget.enums;

public enum SalesAnalyticsGranularity {
    DAY,
    WEEK,
    MONTH,
    YEAR
}
```

- [ ] **Step 3: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 2: Create DTOs

**Files:**
- Create: `src/main/java/com/example/perfume_budget/dto/analytics/SalesAnalyticsResponse.java`
- Create: `src/main/java/com/example/perfume_budget/dto/analytics/RevenueDataPointResponse.java`
- Create: `src/main/java/com/example/perfume_budget/dto/analytics/ProfitDataPointResponse.java`
- Create: `src/main/java/com/example/perfume_budget/dto/analytics/MiniStatResponse.java`
- Create: `src/main/java/com/example/perfume_budget/dto/analytics/OrderStatusSliceResponse.java`

- [ ] **Step 1: Create RevenueDataPointResponse**

```java
package com.example.perfume_budget.dto.analytics;

import java.math.BigDecimal;

public record RevenueDataPointResponse(
        String name,
        BigDecimal revenue
) {}
```

- [ ] **Step 2: Create ProfitDataPointResponse**

```java
package com.example.perfume_budget.dto.analytics;

import java.math.BigDecimal;

public record ProfitDataPointResponse(
        String name,
        BigDecimal grossProfit,
        BigDecimal netProfit
) {}
```

- [ ] **Step 3: Create MiniStatResponse**

```java
package com.example.perfume_budget.dto.analytics;

public record MiniStatResponse(
        String label,
        String value,
        String trend,
        boolean isUp
) {}
```

- [ ] **Step 4: Create OrderStatusSliceResponse**

```java
package com.example.perfume_budget.dto.analytics;

public record OrderStatusSliceResponse(
        String name,
        long value
) {}
```

- [ ] **Step 5: Create SalesAnalyticsResponse**

```java
package com.example.perfume_budget.dto.analytics;

import java.util.List;

public record SalesAnalyticsResponse(
        List<RevenueDataPointResponse> revenueBreakdown,
        List<ProfitDataPointResponse> profitBreakdown,
        List<MiniStatResponse> miniStats,
        List<OrderStatusSliceResponse> orderStatus
) {}
```

- [ ] **Step 6: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 3: Create projections

**Files:**
- Create: `src/main/java/com/example/perfume_budget/projection/RevenueAndCOGSBucketProjection.java`
- Create: `src/main/java/com/example/perfume_budget/projection/ExpenseBucketProjection.java`
- Create: `src/main/java/com/example/perfume_budget/projection/OnlineOrderStatusProjection.java`
- Create: `src/main/java/com/example/perfume_budget/projection/WalkInOrderStatusProjection.java`

- [ ] **Step 1: Create RevenueAndCOGSBucketProjection**

```java
package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface RevenueAndCOGSBucketProjection {
    LocalDateTime getBucketStart();
    BigDecimal getRevenue();
    BigDecimal getCogs();
}
```

- [ ] **Step 2: Create ExpenseBucketProjection**

```java
package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ExpenseBucketProjection {
    LocalDateTime getBucketStart();
    BigDecimal getExpenses();
}
```

- [ ] **Step 3: Create OnlineOrderStatusProjection**

```java
package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.OrderProcessingStatus;

public interface OnlineOrderStatusProjection {
    OrderProcessingStatus getDeliveryStatus();
    Long getCount();
}
```

- [ ] **Step 4: Create WalkInOrderStatusProjection**

```java
package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.WalkInOrderStatus;

public interface WalkInOrderStatusProjection {
    WalkInOrderStatus getStatus();
    Long getCount();
}
```

- [ ] **Step 5: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 4: Add JournalEntryRepository queries

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/repository/JournalEntryRepository.java`

Add Query A (revenue + COGS per bucket, source-filtered) and Query B (operating expenses per bucket, no source filter). Both use native SQL with `DATE_TRUNC` and a `:granularity` string parameter (`'day'`, `'week'`, `'month'`, `'year'`).

- [ ] **Step 1: Add imports at the top of JournalEntryRepository**

Add these imports alongside the existing ones:
```java
import com.example.perfume_budget.projection.RevenueAndCOGSBucketProjection;
import com.example.perfume_budget.projection.ExpenseBucketProjection;
import java.util.List;
```

- [ ] **Step 2: Add Query A — getRevenueAndCOGSByBucket**

Append to `JournalEntryRepository` interface body:

```java
/**
 * Query A: revenue + COGS per time bucket, filtered by journal entry types (source).
 * granularity must be one of: 'day', 'week', 'month', 'year'
 * types must be one or both of: 'SALE', 'WALK_IN_SALE'
 */
@Query(value = """
        SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
               COALESCE(SUM(CASE WHEN a.category = 'SALES_REVENUE' AND l.entry_type = 'CREDIT'
                                 THEN l.amount ELSE 0 END), 0) AS revenue,
               COALESCE(SUM(CASE WHEN a.category = 'COGS' AND l.entry_type = 'DEBIT'
                                 THEN l.amount ELSE 0 END), 0) AS cogs
        FROM journal_entries j
        JOIN journal_entry_lines l ON l.journal_entry_id = j.id
        JOIN ledger_accounts a ON a.id = l.account_id
        WHERE j.type IN (:types)
          AND j.transaction_date BETWEEN :start AND :end
        GROUP BY DATE_TRUNC(:granularity, j.transaction_date)
        ORDER BY bucket_start
        """, nativeQuery = true)
List<RevenueAndCOGSBucketProjection> getRevenueAndCOGSByBucket(
        @Param("types") List<String> types,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end,
        @Param("granularity") String granularity);
```

- [ ] **Step 3: Add Query B — getExpensesByBucket**

Append to `JournalEntryRepository` interface body:

```java
/**
 * Query B: operating expense debits per time bucket — no source filter, always ALL.
 * expenseCategories: list of category names e.g. 'DISCOUNT_EXPENSE', 'MARKETING_EXPENSE' etc.
 * granularity must be one of: 'day', 'week', 'month', 'year'
 */
@Query(value = """
        SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
               COALESCE(SUM(l.amount), 0) AS expenses
        FROM journal_entries j
        JOIN journal_entry_lines l ON l.journal_entry_id = j.id
        JOIN ledger_accounts a ON a.id = l.account_id
        WHERE a.category IN (:expenseCategories)
          AND l.entry_type = 'DEBIT'
          AND j.transaction_date BETWEEN :start AND :end
        GROUP BY DATE_TRUNC(:granularity, j.transaction_date)
        ORDER BY bucket_start
        """, nativeQuery = true)
List<ExpenseBucketProjection> getExpensesByBucket(
        @Param("expenseCategories") List<String> expenseCategories,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end,
        @Param("granularity") String granularity);
```

- [ ] **Step 4: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 5: Add OrderRepository queries

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/repository/OrderRepository.java`

- [ ] **Step 1: Add imports**

Add these imports alongside the existing ones:
```java
import com.example.perfume_budget.projection.OnlineOrderStatusProjection;
```

- [ ] **Step 2: Add countCompletedOnlineOrders**

Append to `OrderRepository` interface body:

```java
@Query("""
        SELECT COUNT(o.id)
        FROM Order o
        WHERE o.status = com.example.perfume_budget.enums.PaymentStatus.COMPLETED
          AND o.createdAt BETWEEN :start AND :end
        """)
long countCompletedOnlineOrders(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
```

- [ ] **Step 3: Add countOnlineOrdersByDeliveryStatus**

Append to `OrderRepository` interface body:

```java
@Query("""
        SELECT o.deliveryStatus AS deliveryStatus, COUNT(o.id) AS count
        FROM Order o
        WHERE o.createdAt BETWEEN :start AND :end
        GROUP BY o.deliveryStatus
        """)
List<OnlineOrderStatusProjection> countOnlineOrdersByDeliveryStatus(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
```

- [ ] **Step 4: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 6: Add WalkInOrderRepository queries

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/repository/WalkInOrderRepository.java`

- [ ] **Step 1: Add required imports**

Add these imports:
```java
import com.example.perfume_budget.enums.WalkInOrderStatus;
import com.example.perfume_budget.projection.WalkInOrderStatusProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
```

- [ ] **Step 2: Add countCompletedWalkInOrders**

Append to `WalkInOrderRepository` interface body:

```java
@Query("""
        SELECT COUNT(w.id)
        FROM WalkInOrder w
        WHERE w.status = com.example.perfume_budget.enums.WalkInOrderStatus.COMPLETED
          AND w.createdAt BETWEEN :start AND :end
        """)
long countCompletedWalkInOrders(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
```

- [ ] **Step 3: Add countWalkInOrdersByStatus**

Append to `WalkInOrderRepository` interface body:

```java
@Query("""
        SELECT w.status AS status, COUNT(w.id) AS count
        FROM WalkInOrder w
        WHERE w.createdAt BETWEEN :start AND :end
        GROUP BY w.status
        """)
List<WalkInOrderStatusProjection> countWalkInOrdersByStatus(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
```

- [ ] **Step 4: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 7: Update service interface and add stub

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/service/interfaces/AdminMetricService.java`
- Modify: `src/main/java/com/example/perfume_budget/service/AdminMetricServiceImpl.java`

- [ ] **Step 1: Add method to AdminMetricService interface**

Add these imports to `AdminMetricService.java`:
```java
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
```

Add this method signature to the interface body:
```java
SalesAnalyticsResponse getSalesAnalytics(
        SalesAnalyticsSource source,
        SalesAnalyticsGranularity granularity,
        LocalDate from,
        LocalDate to);
```

- [ ] **Step 2: Add stub implementation to AdminMetricServiceImpl**

Add these imports to `AdminMetricServiceImpl.java`:
```java
import com.example.perfume_budget.dto.analytics.*;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.projection.*;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.WalkInOrderRepository;
```

Add two new repository fields to the class (after the existing fields):
```java
private final JournalEntryRepository journalEntryRepository;
private final WalkInOrderRepository walkInOrderRepository;
```

Add stub method to `AdminMetricServiceImpl`:
```java
@Override
public SalesAnalyticsResponse getSalesAnalytics(
        SalesAnalyticsSource source,
        SalesAnalyticsGranularity granularity,
        LocalDate from,
        LocalDate to) {
    throw new UnsupportedOperationException("not implemented yet");
}
```

- [ ] **Step 3: Compile check**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

---

### Task 8: Write service unit tests (failing)

**Files:**
- Create: `src/test/java/com/example/perfume_budget/service/SalesAnalyticsServiceTest.java`

- [ ] **Step 1: Create the test class**

```java
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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
        verify(journalEntryRepository).getRevenueAndCOGSByBucket(
                eq(List.of("SALE")), any(), any(), eq("day"));
    }

    @Test
    void source_WALK_IN_passesOnlyWalkInSaleType() {
        stubAllReposEmpty();
        service.getSalesAnalytics(SalesAnalyticsSource.WALK_IN, SalesAnalyticsGranularity.DAY, null, null);
        verify(journalEntryRepository).getRevenueAndCOGSByBucket(
                eq(List.of("WALK_IN_SALE")), any(), any(), eq("day"));
    }

    @Test
    void source_ALL_passesBothTypes() {
        stubAllReposEmpty();
        service.getSalesAnalytics(SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        verify(journalEntryRepository).getRevenueAndCOGSByBucket(
                eq(List.of("SALE", "WALK_IN_SALE")), any(), any(), eq("day"));
    }

    // ----- granularity bucket label tests -----

    @Test
    void granularity_DAY_producesSevenBucketsWithDayLabels() {
        stubAllReposEmpty();
        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);
        assertEquals(7, r.revenueBreakdown().size());
        // labels are short day names: Mon, Tue, etc.
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
        // DAY default = 7 buckets; only supply data for one day
        LocalDateTime dayBucket = LocalDate.now().minusDays(3).atStartOfDay();
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(dayBucket, 200.0, 80.0)));
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
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(dayBucket, 300.0, 100.0)));
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(expBucket(dayBucket, 50.0)));
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
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(dayBucket, 100.0, 50.0))); // grossProfit=50
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(expBucket(dayBucket, 200.0))); // expenses=200 → netProfit=-150
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
        // revenue current=120, previous=100 → 20% up
        LocalDateTime curBucket = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime prevBucket = LocalDate.now().minusDays(8).atStartOfDay();

        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(curBucket, 120.0, 0.0)))
                .thenReturn(List.of(revCogsBucket(prevBucket, 100.0, 0.0)));
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

        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(curBucket, 80.0, 0.0)))
                .thenReturn(List.of(revCogsBucket(prevBucket, 100.0, 0.0)));
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
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of(revCogsBucket(curBucket, 50.0, 0.0)))
                .thenReturn(List.of()); // previous = empty
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
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any()))
                .thenReturn(List.of(
                        onlineStatus(OrderProcessingStatus.DELIVERED, 5),
                        onlineStatus(OrderProcessingStatus.PENDING, 2)
                ));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ONLINE, SalesAnalyticsGranularity.DAY, null, null);

        List<String> names = r.orderStatus().stream().map(OrderStatusSliceResponse::name).toList();
        assertTrue(names.contains("Delivered"));
        assertTrue(names.contains("Pending"));
        assertFalse(names.contains("Completed"));
    }

    @Test
    void orderStatus_WALK_IN_returnsOnlyWalkInLabels() {
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any()))
                .thenReturn(List.of(
                        walkInStatus(WalkInOrderStatus.COMPLETED, 10),
                        walkInStatus(WalkInOrderStatus.CANCELLED, 1)
                ));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.WALK_IN, SalesAnalyticsGranularity.DAY, null, null);

        List<String> names = r.orderStatus().stream().map(OrderStatusSliceResponse::name).toList();
        assertTrue(names.contains("Completed"));
        assertTrue(names.contains("Cancelled"));
        assertFalse(names.contains("Delivered"));
    }

    @Test
    void orderStatus_ALL_combinesBothSourcesNormalized() {
        when(journalEntryRepository.getRevenueAndCOGSByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(journalEntryRepository.getExpensesByBucket(anyList(), any(), any(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countCompletedOnlineOrders(any(), any())).thenReturn(0L);
        when(walkInOrderRepository.countCompletedWalkInOrders(any(), any())).thenReturn(0L);
        when(orderRepository.countOnlineOrdersByDeliveryStatus(any(), any()))
                .thenReturn(List.of(
                        onlineStatus(OrderProcessingStatus.CANCELLED, 3)
                ));
        when(walkInOrderRepository.countWalkInOrdersByStatus(any(), any()))
                .thenReturn(List.of(
                        walkInStatus(WalkInOrderStatus.CANCELLED, 2)
                ));

        SalesAnalyticsResponse r = service.getSalesAnalytics(
                SalesAnalyticsSource.ALL, SalesAnalyticsGranularity.DAY, null, null);

        // Both CANCELLED contributions merge into a single "Cancelled" slice
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
```

- [ ] **Step 2: Run tests — expect failures**

Run: `./mvnw test -pl . -Dtest=SalesAnalyticsServiceTest -q`
Expected: Tests FAIL with `UnsupportedOperationException`

---

### Task 9: Implement service — helpers and main method

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/service/AdminMetricServiceImpl.java`

Replace the stub `getSalesAnalytics()` method with the full implementation and add all private helpers.

- [ ] **Step 1: Add remaining imports**

Add to `AdminMetricServiceImpl.java` imports:
```java
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
```

- [ ] **Step 2: Add private ResolvedAnalyticsWindow record**

Add this inside the `AdminMetricServiceImpl` class body (before or after the existing private helpers):
```java
private record ResolvedAnalyticsWindow(
        LocalDateTime currentStart,
        LocalDateTime currentEnd,
        LocalDateTime previousStart,
        LocalDateTime previousEnd
) {}
```

- [ ] **Step 3: Add EXPENSE_CATEGORIES constant**

Add inside the class body:
```java
private static final List<String> EXPENSE_CATEGORIES = List.of(
        "DISCOUNT_EXPENSE", "COUPON_EXPENSE", "MARKETING_EXPENSE",
        "LOGISTICS_EXPENSE", "MISCELLANEOUS_EXPENSE", "GENERAL_EXPENSE"
);
```

- [ ] **Step 4: Add resolveWindow helper**

```java
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
```

- [ ] **Step 5: Add journalEntryTypesFor helper**

```java
private List<String> journalEntryTypesFor(SalesAnalyticsSource source) {
    return switch (source) {
        case ONLINE  -> List.of("SALE");
        case WALK_IN -> List.of("WALK_IN_SALE");
        case ALL     -> List.of("SALE", "WALK_IN_SALE");
    };
}
```

- [ ] **Step 6: Add bucket generation helpers**

```java
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
```

- [ ] **Step 7: Add labelForBucket helper**

```java
private String labelForBucket(SalesAnalyticsGranularity granularity, LocalDateTime bucket) {
    return switch (granularity) {
        case DAY   -> bucket.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        case WEEK  -> bucket.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
        case MONTH -> bucket.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        case YEAR  -> String.valueOf(bucket.getYear());
    };
}
```

- [ ] **Step 8: Add trend helpers**

```java
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
```

- [ ] **Step 9: Add aggregation sum helpers**

```java
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
```

- [ ] **Step 10: Add buildRevenueBreakdown helper**

```java
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
```

- [ ] **Step 11: Add buildProfitBreakdown helper**

```java
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
```

- [ ] **Step 12: Add buildMiniStats helper**

```java
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
            moneyMiniStat("Avg Order Value",      currentAOV,                         previousAOV),
            moneyMiniStat("Total Revenue",        currentRevenue,                     previousRevenue),
            countMiniStat("Total Orders",         currentOrders,                      previousOrders),
            moneyMiniStat("Total Gross Profit",   currentGP,                          previousGP),
            moneyMiniStat("Total Net Profit",     currentNP,                          previousNP)
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
    BigDecimal cur = BigDecimal.valueOf(current);
    BigDecimal prev = BigDecimal.valueOf(previous);
    BigDecimal trend = calculateTrendPercent(cur, prev);
    boolean isUp = current >= previous;
    return new MiniStatResponse(label, String.valueOf(current), formatTrend(trend), isUp);
}
```

- [ ] **Step 13: Add buildOrderStatus helper**

```java
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
```

- [ ] **Step 14: Replace stub with full getSalesAnalytics() implementation**

Replace the `throw new UnsupportedOperationException(...)` stub entirely:

```java
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

    long currentOnlineOrders   = includeOnline  ? orderRepository.countCompletedOnlineOrders(window.currentStart(),  window.currentEnd())  : 0L;
    long previousOnlineOrders  = includeOnline  ? orderRepository.countCompletedOnlineOrders(window.previousStart(), window.previousEnd()) : 0L;
    long currentWalkInOrders   = includeWalkIn  ? walkInOrderRepository.countCompletedWalkInOrders(window.currentStart(),  window.currentEnd())  : 0L;
    long previousWalkInOrders  = includeWalkIn  ? walkInOrderRepository.countCompletedWalkInOrders(window.previousStart(), window.previousEnd()) : 0L;

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
    BigDecimal currentRevenue  = sumRevenue(currentRevCogs);
    BigDecimal previousRevenue = sumRevenue(previousRevCogs);
    BigDecimal currentCogs     = sumCogs(currentRevCogs);
    BigDecimal previousCogs    = sumCogs(previousRevCogs);
    BigDecimal currentExpTotal = sumExpenses(currentExpenses);
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
```

- [ ] **Step 15: Run service tests — expect all to pass**

Run: `./mvnw test -pl . -Dtest=SalesAnalyticsServiceTest -q`
Expected: All tests PASS

---

### Task 10: Write controller tests (failing)

**Files:**
- Create: `src/test/java/com/example/perfume_budget/controller/SalesAnalyticsControllerTest.java`

- [ ] **Step 1: Create the test class**

```java
package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.analytics.*;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.service.interfaces.AdminMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminMetricController.class)
class SalesAnalyticsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AdminMetricService adminMetricService;

    private SalesAnalyticsResponse emptyResponse() {
        return new SalesAnalyticsResponse(List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void defaults_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ALL), eq(SalesAnalyticsGranularity.DAY),
                isNull(), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revenueBreakdown").isArray())
                .andExpect(jsonPath("$.data.profitBreakdown").isArray())
                .andExpect(jsonPath("$.data.miniStats").isArray())
                .andExpect(jsonPath("$.data.orderStatus").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void explicitSourceAndGranularity_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ONLINE), eq(SalesAnalyticsGranularity.WEEK),
                isNull(), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("source", "ONLINE")
                        .param("granularity", "WEEK"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validFromAndTo_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ALL), eq(SalesAnalyticsGranularity.DAY),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 3, 31))))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-03-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run tests — expect failures**

Run: `./mvnw test -pl . -Dtest=SalesAnalyticsControllerTest -q`
Expected: Tests FAIL (endpoint does not exist → 404 / compile errors)

---

### Task 11: Add controller endpoint

**Files:**
- Modify: `src/main/java/com/example/perfume_budget/controller/AdminMetricController.java`

- [ ] **Step 1: Add imports**

Add to `AdminMetricController.java`:
```java
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.exception.BadRequestException;
```

- [ ] **Step 2: Add the endpoint**

Append to the `AdminMetricController` class body:

```java
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
```

- [ ] **Step 3: Run controller tests — expect all to pass**

Run: `./mvnw test -pl . -Dtest=SalesAnalyticsControllerTest -q`
Expected: All tests PASS

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS, no regressions

- [ ] **Step 5: Compile full project**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

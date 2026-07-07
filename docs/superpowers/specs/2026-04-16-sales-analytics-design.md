# Sales Analytics Endpoint Design

**Date:** 2026-04-16
**Status:** Approved

## Summary

Implement one admin analytics endpoint returning a full analytics payload for a selected sales source and time granularity. The response covers revenue, gross profit, and net profit — both as time-series chart data and summary cards with trend indicators.

---

## API Contract

**Endpoint:** `GET /api/v1/admin/metrics/sales-analytics`

**Query Parameters:**

| Param | Type | Default | Enum |
|-------|------|---------|------|
| source | SalesAnalyticsSource | ALL | ALL, ONLINE, WALK_IN |
| granularity | SalesAnalyticsGranularity | DAY | DAY, WEEK, MONTH, YEAR |
| from | LocalDate | null | — |
| to | LocalDate | null | — |

**Validation:**
- If only one of `from` / `to` is provided → 400
- If `from > to` → 400
- If both absent → derive default range from granularity
- Apply dates inclusively: `start = from.atStartOfDay()`, `end = to.atTime(23, 59, 59)`

**Default date ranges:**

| Granularity | Default Window |
|-------------|----------------|
| DAY | Last 7 calendar days including today |
| WEEK | Last 8 calendar weeks including current week |
| MONTH | Last 12 calendar months including current month |
| YEAR | Last 5 calendar years including current year |

---

## Response Shape

```typescript
interface RevenueDataPoint {
  name: string;        // bucket label e.g. "Mon", "Jan", "2026"
  revenue: number;
}

interface ProfitDataPoint {
  name: string;        // same bucket label as revenueBreakdown
  grossProfit: number;
  netProfit: number;
}

interface MiniStat {
  label: string;
  value: string;
  trend: string;       // plain percentage e.g. "12%"
  isUp: boolean;
}

interface OrderStatusDatum {
  name: string;
  value: number;
}

interface SalesAnalyticsResponse {
  revenueBreakdown: RevenueDataPoint[];
  profitBreakdown: ProfitDataPoint[];   // NEW — same buckets as revenueBreakdown
  miniStats: MiniStat[];                // 5 cards
  orderStatus: OrderStatusDatum[];
}
```

Wrapped in existing API envelope:
```json
{
  "description": null,
  "data": {
    "revenueBreakdown": [],
    "profitBreakdown": [],
    "miniStats": [],
    "orderStatus": []
  }
}
```

---

## miniStats Cards (5 total)

| # | Label | Source | Trend Comparison |
|---|-------|--------|-----------------|
| 1 | Avg Order Value | revenue / completed orders | vs previous period |
| 2 | Total Revenue | SALES_REVENUE credits, source-filtered | vs previous period |
| 3 | Total Orders | completed order count, source-filtered | vs previous period |
| 4 | Total Gross Profit | revenue − COGS, source-filtered | vs previous period |
| 5 | Total Net Profit | gross profit − all operating expenses, always ALL | vs previous period |

---

## Data Source Rules

### Revenue
- Source: `JournalEntryLine` where `account.category = SALES_REVENUE` and `entryType = CREDIT`
- Journal entry types: `SALE` (online), `WALK_IN_SALE` (walk-in), both for ALL
- Source-filtered

### COGS
- Source: `JournalEntryLine` where `account.category = COGS` and `entryType = DEBIT`
- Same journal entry type filter as revenue (SALE / WALK_IN_SALE)
- Source-filtered
- Gross Profit = Revenue − COGS

### Operating Expenses (for Net Profit)
- Source: `JournalEntryLine` where `account.category IN (DISCOUNT_EXPENSE, COUPON_EXPENSE, MARKETING_EXPENSE, LOGISTICS_EXPENSE, MISCELLANEOUS_EXPENSE, GENERAL_EXPENSE)` and `entryType = DEBIT`
- No filter on `journal_entry.type` — includes manual entries
- **Always ALL regardless of source filter**
- Net Profit = Gross Profit − Operating Expenses

### Order Counts
- Online: `Order` table, `PaymentStatus = COMPLETED`
- Walk-in: `WalkInOrder` table, `status = COMPLETED`
- ALL: sum of both

### Order Status
- Online: `Order.deliveryStatus` — labels: Pending, Packing, Out for Delivery, Delivered, Cancelled
- Walk-in: `WalkInOrder.status` — labels: Completed, Cancelled, Refunded
- ALL: combined normalized set — Completed, Pending, Packing, Out for Delivery, Delivered, Cancelled, Refunded

---

## Repository Queries

### JournalEntryRepository — Query A (Revenue + COGS, source-filtered)

Native SQL. One query per granularity (DAY, WEEK, MONTH, YEAR).

```sql
SELECT
  DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
  SUM(CASE WHEN a.category = 'SALES_REVENUE' AND l.entry_type = 'CREDIT' THEN l.amount ELSE 0 END) AS revenue,
  SUM(CASE WHEN a.category = 'COGS'          AND l.entry_type = 'DEBIT'  THEN l.amount ELSE 0 END) AS cogs
FROM journal_entries j
JOIN journal_entry_lines l ON l.journal_entry_id = j.id
JOIN ledger_accounts a ON a.id = l.account_id
WHERE j.type IN (:types)
  AND j.transaction_date BETWEEN :start AND :end
GROUP BY DATE_TRUNC(:granularity, j.transaction_date)
ORDER BY bucket_start
```

Projection: `RevenueAndCOGSBucketProjection { getBucketStart(), getRevenue(), getCogs() }`

### JournalEntryRepository — Query B (Operating Expenses, always ALL)

```sql
SELECT
  DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
  SUM(l.amount) AS expenses
FROM journal_entries j
JOIN journal_entry_lines l ON l.journal_entry_id = j.id
JOIN ledger_accounts a ON a.id = l.account_id
WHERE a.category IN ('DISCOUNT_EXPENSE','COUPON_EXPENSE','MARKETING_EXPENSE',
                     'LOGISTICS_EXPENSE','MISCELLANEOUS_EXPENSE','GENERAL_EXPENSE')
  AND l.entry_type = 'DEBIT'
  AND j.transaction_date BETWEEN :start AND :end
GROUP BY DATE_TRUNC(:granularity, j.transaction_date)
ORDER BY bucket_start
```

Projection: `ExpenseBucketProjection { getBucketStart(), getExpenses() }`

Both queries run twice — once for current window, once for previous window (same length).

### OrderRepository additions
- Count completed online orders in date range
- Count online orders by `deliveryStatus` in date range

### WalkInOrderRepository additions
- Count completed walk-in orders in date range
- Count walk-in orders by `status` in date range

Projection: `OrderStatusCountProjection { getStatus(), getCount() }`

---

## Service Implementation

### Method Signature

```java
SalesAnalyticsResponse getSalesAnalytics(
    SalesAnalyticsSource source,
    SalesAnalyticsGranularity granularity,
    @Nullable LocalDate from,
    @Nullable LocalDate to
);
```

### Orchestration Steps

1. Normalize nulls: source → ALL, granularity → DAY
2. Resolve current window (currentStart, currentEnd)
3. Resolve previous window (same length, immediately preceding)
4. Run Query A current → `revenueAndCOGSCurrent`
5. Run Query A previous → `revenueAndCOGSPrevious`
6. Run Query B current → `expensesCurrent`
7. Run Query B previous → `expensesPrevious`
8. Run order count queries (current + previous)
9. Run order status queries
10. Build `revenueBreakdown`
11. Build `profitBreakdown`
12. Build `miniStats` (5 cards)
13. Build `orderStatus`
14. Return assembled response

### Private Helpers

```java
ResolvedAnalyticsWindow resolveWindow(granularity, from, to)
List<RevenueDataPointResponse> buildRevenueBreakdown(revenueAndCOGSBuckets, allBuckets, granularity)
List<ProfitDataPointResponse> buildProfitBreakdown(revenueAndCOGSBuckets, expenseBuckets, allBuckets, granularity)
List<MiniStatResponse> buildMiniStats(...)
List<OrderStatusSliceResponse> buildOrderStatus(source, onlineStatuses, walkInStatuses)
BigDecimal sumRevenue(List<RevenueAndCOGSBucketProjection>)
BigDecimal sumCogs(List<RevenueAndCOGSBucketProjection>)
BigDecimal sumExpenses(List<ExpenseBucketProjection>)
BigDecimal calculateTrendPercent(current, previous)
String formatTrend(BigDecimal percent)
String labelForBucket(granularity, bucketStart)
```

### Profit Calculation Per Bucket

```
grossProfit = revenue - cogs          (source-filtered)
netProfit   = grossProfit - expenses  (expenses always ALL)
```

Net profit may be negative — return as-is, no clamping.

### Trend Formula

```
if previous > 0:  ((current - previous) / previous) * 100
if previous == 0 and current == 0:  0%, isUp = true
if previous == 0 and current > 0:  100%, isUp = true
isUp = current >= previous
```

Trend string: plain percentage without sign, e.g. `"12%"`, `"0%"`.

---

## New Files

### DTOs (`dto/analytics/`)
- `SalesAnalyticsResponse` — revenueBreakdown, profitBreakdown, miniStats, orderStatus
- `RevenueDataPointResponse` — name, revenue
- `ProfitDataPointResponse` — name, grossProfit, netProfit
- `MiniStatResponse` — label, value, trend, isUp
- `OrderStatusSliceResponse` — name, value

### Enums (`enums/`)
- `SalesAnalyticsSource` — ALL, ONLINE, WALK_IN
- `SalesAnalyticsGranularity` — DAY, WEEK, MONTH, YEAR

### Projections (`projection/`)
- `RevenueAndCOGSBucketProjection` — getBucketStart(), getRevenue(), getCogs()
- `ExpenseBucketProjection` — getBucketStart(), getExpenses()
- `OrderStatusCountProjection` — getStatus(), getCount()

---

## Files Changed

| File | Change |
|------|--------|
| `controller/AdminMetricController.java` | Add GET /sales-analytics, bind params, delegate |
| `service/interfaces/AdminMetricService.java` | Add getSalesAnalytics() signature |
| `service/AdminMetricServiceImpl.java` | Full implementation |
| `repository/JournalEntryRepository.java` | Add Query A and Query B (4 granularities each) |
| `repository/OrderRepository.java` | Add count + status queries |
| `repository/WalkInOrderRepository.java` | Add count + status queries |

---

## Bucket Rules

| Granularity | Label Format | Bucket Key |
|-------------|-------------|-----------|
| DAY | Mon | Calendar date |
| WEEK | Feb 18 | ISO week start date |
| MONTH | Jan | Calendar month |
| YEAR | 2026 | Calendar year |

Zero-fill: generate full expected bucket list → merge DB results → missing buckets get `revenue: 0`, `grossProfit: 0`, `netProfit: 0`. Preserve chronological order.

---

## Previous Period Rule

Previous window length = current window length exactly.

Examples:
- Current = 7 days → previous = preceding 7 days
- Current = 8 weeks → previous = preceding 8 weeks
- Current = 12 months → previous = preceding 12 months

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| No revenue, COGS, or expenses in period | profitBreakdown zero-filled; all profit miniStats = GHS 0.00 |
| Expenses exceed gross profit | Net profit negative — return as-is, no clamping |
| source=ONLINE or WALK_IN | Expenses still fully deducted (always ALL rule) |
| Previous period empty | Trend = 100% isUp=true if current > 0, else 0% isUp=true |
| COGS > revenue in a bucket | grossProfit negative — allowed |
| No expense entries in period | expenses = 0, netProfit = grossProfit |
| source=ALL, mixed COGS | SALE + WALK_IN_SALE COGS both summed in Query A |
| Only one of from/to provided | Reject 400 |
| Refund entries | Ignored in v1 (refund posting flow not active) |
| Timezone | Use server-local date boundaries consistently |

---

## Exact Metric Semantics

**Revenue:** SALES_REVENUE credit lines only. Excludes tax, discounts as standalone lines, COGS, inventory movements, cash/mobile-money debit lines.

**Gross Profit:** Revenue − COGS. COGS from SALE/WALK_IN_SALE entries only. Source-filtered.

**Net Profit:** Gross Profit − all operating expense debits (DISCOUNT_EXPENSE, COUPON_EXPENSE, MARKETING_EXPENSE, LOGISTICS_EXPENSE, MISCELLANEOUS_EXPENSE, GENERAL_EXPENSE). Always business-wide regardless of source filter.

**Avg Order Value:** Revenue / completed order count. ALL uses combined totals.

**Total Orders:** Completed orders only (PaymentStatus.COMPLETED for online, WalkInOrderStatus.COMPLETED for walk-in).

**Order Status:** Based on order tables, not journal entries. Based on creation date, not payment date. All orders in period regardless of payment completion.

---

## Assumptions

- Currency formatting: `Money(...).toString()` — GHS conventions.
- Chart values: raw numeric amounts, not pre-formatted strings.
- Net profit can be negative — frontend must handle negative values.
- v1 does not subtract refunds from revenue or expenses until refund-bookkeeping flow exists.
- Operating expense categories are exhaustive for v1: DISCOUNT_EXPENSE, COUPON_EXPENSE, MARKETING_EXPENSE, LOGISTICS_EXPENSE, MISCELLANEOUS_EXPENSE, GENERAL_EXPENSE.
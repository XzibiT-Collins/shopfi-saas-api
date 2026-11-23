# Admin accounting API: journal entries (CAT-03)

Routes under `/api/v1/admin/accounting` are tenant scoped and `ADMIN` only (anonymous: 401, other roles: 403; see
[authorization-matrix.md](authorization-matrix.md)). Every response is `{ "description": string|null, "data": ... }`;
errors carry a stable machine code in `data.code`, never parse `description`.

This page started with CAT-03 (journal paging) and the CAT-04 / CAT-06 date rules; API-RD-12 (the accounting part of
API-RD-02) completes it: every route is now documented below.

| Method | Path | Purpose |
|---|---|---|
| GET | `/summary` | point-in-time totals of the active accounts |
| GET | `/income-statement?startDate&endDate` | revenue, COGS, expenses and profit of a period |
| GET | `/balance-sheet` | point-in-time assets, liabilities, equity |
| GET | `/cash-flow?startDate&endDate` | daily cash in / out of the CASH account |
| GET | `/journal-entries?startDate&endDate&page&size` | paged journal (below) |
| GET | `/journal-entries/reference?referenceType&referenceId` | every entry of one order / payment (unpaged, bounded by one reference) |
| GET | `/journal-entries/manual` | every manual entry, newest first (unpaged: audit list) |
| GET | `/metadata` | account categories and journal entry types for select fields |
| POST | `/api/v1/admin/bookkeeping/manual-entry` | record a manual entry (below; `ADMIN`) |

## Reports and metadata (API-RD-12g)

All amounts are JSON numbers with two decimals; every report below carries `currency` (API-RD-12h, below).

`GET /summary` (no parameters):

```json summary-response
{
  "description": null,
  "data": {
    "totalRevenue": 5200.00, "totalExpenses": 3100.00, "totalAssets": 9000.00, "totalLiabilities": 800.00,
    "netProfit": 2100.00, "cashBalance": 4100.00, "currency": "GHS"
  }
}
```

It sums the **stored balances** of the active accounts (`netProfit` = revenue - expenses over all time, `cashBalance` =
the CASH category). It is not a period report.

`GET /income-statement`: for each active REVENUE / EXPENSE account the period's net (credits - debits for revenue,
debits - credits for expenses); an account whose period balance is zero or negative is left out of the two lists, and
the totals add the listed accounts only.
`totalCOGS` is the COGS category among the expenses, `grossProfit = totalRevenue - totalCOGS`,
`netProfit = totalRevenue - totalExpenses`.

```json income-statement-response
{
  "description": null,
  "data": {
    "totalRevenue": 42.00, "totalCOGS": 0.00, "grossProfit": 42.00, "totalExpenses": 0.00, "netProfit": 42.00,
    "revenueAccounts": [
      { "accountCode": "4000", "accountName": "Sales Revenue", "accountType": "REVENUE", "accountCategory": "SALES_REVENUE", "balance": 42.00 }
    ],
    "expenseAccounts": [],
    "currency": "GHS"
  }
}
```

`GET /balance-sheet` (no parameters): the stored balances of the active ASSET, LIABILITY and EQUITY accounts plus one
**virtual** equity row for retained earnings (below). `totalEquity` includes it.

```json balance-sheet-response
{
  "description": null,
  "data": {
    "totalAssets": 100.00, "totalLiabilities": 10.00, "totalEquity": 90.00,
    "assetAccounts": [
      { "accountCode": "1000", "accountName": "Cash and Bank", "accountType": "ASSET", "accountCategory": "CASH", "balance": 100.00 }
    ],
    "liabilityAccounts": [
      { "accountCode": "2000", "accountName": "Tax Payable", "accountType": "LIABILITY", "accountCategory": "TAX_PAYABLE", "balance": 10.00 }
    ],
    "equityAccounts": [
      { "accountCode": "3200", "accountName": "Inventory Revaluation", "accountType": "EQUITY", "accountCategory": "INVENTORY_REVALUATION", "balance": 0.00 },
      { "accountCode": "RETAINED_EARNINGS", "accountName": "Retained Earnings", "accountType": "EQUITY", "accountCategory": "RETAINED_EARNINGS", "balance": 90.00 }
    ],
    "currency": "GHS"
  }
}
```

`GET /metadata`: `accountCategories` (every `AccountCategory`, the values a manual-entry line takes as `accountCategory`)
and `journalEntryTypes` (every `JournalEntryType`, the values a manual entry takes as `type`; `EXPENSE` was added by
API-RD-12c). `label` is the humanised name.

```json metadata-response
{
  "description": null,
  "data": {
    "accountCategories": [ { "value": "CASH", "label": "Cash" }, { "value": "SALES_REVENUE", "label": "Sales Revenue" } ],
    "journalEntryTypes": [ { "value": "ADJUSTMENT", "label": "Adjustment" }, { "value": "EXPENSE", "label": "Expense" } ]
  }
}
```

## Date window of income statement and cash flow (API-RD-12a)

`/income-statement` and `/cash-flow` used `startDate 00:00:00 .. endDate 23:59:59` inclusive, so an entry in the last
fraction of a second of a day (`23:59:59.500`) was in the journal but missing from both reports. They now read the
**same half-open window as the journal list**: `startDate 00:00 <= transactionDate < (endDate + 1 day) 00:00`
(server-local time, like `transactionDate`). The three ranged routes now agree for every instant. The sales
analytics reports (`SalesReportController`) still use their own bounds and are not part of this change.

## Cash flow is CASH only (API-RD-12b): documented, not changed

`/cash-flow` covers accounts of category `CASH` ("Cash and Bank", 1000) only. **Mobile money (1001, category
`MOBILE_MONEY`) is excluded**, so a mobile-money or split walk-in sale shows in revenue but not in this report.
Decision: documented, not widened. Including it would change the meaning of `totalInflows`/`netCashFlow` for every
existing reader and the product has not decided whether the report should be one "cash and mobile money" number, two
series, or a selector. A later release can add an additive `?accounts=CASH,MOBILE_MONEY` filter (default `CASH`), which
keeps today's answer. Until then, the summary's `cashBalance` is also CASH only.

## EXPENSE journal type (API-RD-12c)

`JournalEntryType.EXPENSE` was added. The type is stored in the existing `VARCHAR` column (no check constraint), so
**no migration** is needed and old rows are unaffected. A manual entry of type `EXPENSE` posts exactly like any manual
entry: the ledger effect comes from its lines (for rent: debit `GENERAL_EXPENSE`, credit `CASH`), and it appears in
`/metadata` and the journal with `"type": "EXPENSE"`. Reports key off account categories, not the entry type, so an
`EXPENSE` entry and an `ADJUSTMENT` with the same lines produce the same income statement. Clients that switch over
`type` must tolerate the new value.

## POST /api/v1/admin/bookkeeping/manual-entry (API-RD-12d)

`ADMIN` only (anonymous `401`, others `403`), tenant scoped. Body `ManualJournalEntryRequest`; response `200`
`{ "description": null, "data": null }`.

```json manual-entry-request
{
  "description": "Shop rent, November",
  "type": "EXPENSE",
  "lines": [
    { "accountCategory": "GENERAL_EXPENSE", "entryType": "DEBIT", "amount": 1200.00, "description": "Rent" },
    { "accountCategory": "CASH", "entryType": "CREDIT", "amount": 1200.00, "description": "Paid from till" }
  ]
}
```

**Amount check.** Each line `amount` must be greater than zero, have at most two decimal places and fit the ledger
(`DECIMAL(19,2)`): otherwise `400 INVALID_AMOUNT`, nothing posted. Before, zero and negative amounts were accepted (a
negative line could silently flip its side and a `0` line was a no-op entry). Direction is expressed by `entryType`.
The existing refusals are unchanged: unbalanced entry `400` (`Journal entry is unbalanced...`), unknown category `400`,
a posting that would take an account below zero `400` (`<account> cannot go negative...`, uncoded, as before).

**Idempotency-Key (optional header).** Same format as checkout and walk-in: 8-64 characters of `A-Za-z0-9._:-`
(`400 IDEMPOTENCY_KEY_INVALID`).

* Scope: tenant + admin + key. The key, the admin's id and a SHA-256 fingerprint of the normalised body (line order and
  amount scale ignored) are stored on the entry (`journal_entries.idempotency_key`, `idempotency_user_id`,
  `request_fingerprint`, Flyway `V20`, unique per admin and key); the body itself is not kept.
* Same key + same body: `200`, **nothing is posted** (no second entry, no second balance change), response header
  `Idempotency-Replayed: true`. The first call answers `Idempotency-Replayed: false`. The body stays `data: null`.
* Same key + different body: `409 IDEMPOTENCY_KEY_REUSED`, nothing written.
* Concurrent requests with one key: serialised on the admin's user row; exactly one entry is posted, the rest replay.
* A refused request (`INVALID_AMOUNT`, unbalanced, ...) does not consume the key; the same key can be retried with a
  corrected body.
* **No header: unchanged** (each POST is a new entry, no replay protection), so existing clients keep working. Clients
  SHOULD send a fresh key per entry form and re-send it on every retry. No TTL: the key lives as long as the entry.
* `Idempotency-Replayed` is readable cross-origin only if the CORS exposed-headers list names it (today it lists
  `ETag`, `Retry-After`); same-origin and server-side clients read it directly.

## Journal paging past row 10,000 (API-RD-12e): capped, documented, not changed

The journal list is offset-paged and **cannot page past row 10,000 by design** (CAT-03): `page * size >= 10,000` or
`page > 1,000,000` is `400 INVALID_PAGE` with `data.maxOffset` (10,000) and `data.size`. `size` is clamped to 50. The
cap is explicit and tested (an unbounded `OFFSET` on a large ledger is a cost and DoS risk). Keyset paging is not added:
no screen needs a deep walk, and the answer to "I need older rows" is a narrower date range (any range up to 10,000
entries is fully reachable; a day is almost always far below that). If an export is ever needed it should be its own
streaming endpoint rather than deeper offsets.

## Retained Earnings row on the balance sheet (API-RD-12f)

The balance sheet's virtual Retained Earnings row used `accountCode: "3200"`, which is the real account **Inventory
Revaluation**, so two equity rows shared a code. The virtual row now has `accountCode: "RETAINED_EARNINGS"` (not a
chart-of-accounts code: those are numeric, and nothing can be posted to it). `accountName`, `accountType` (`EQUITY`) and
`accountCategory` (`RETAINED_EARNINGS`) are unchanged, and its `balance` is still all revenue minus all expense balances.
Clients that rendered rows keyed by `accountCode` can now rely on uniqueness; a client that looked for the code `3200` to
find retained earnings must look for `accountCategory = RETAINED_EARNINGS` (or the new code) instead.

## Currency (API-RD-12h)

Every report (`/summary`, `/income-statement`, `/balance-sheet`, `/cash-flow`) and every journal entry now carries an
additive `"currency": "GHS"` (ISO code). The ledger is single-currency (GHS; ledger descriptions already say GHS), so
the value is constant today; clients should read it instead of hard-coding the unit. Amounts stay JSON numbers (not
strings with a currency prefix like walk-in): changing them would break every reader, so it is not done. A multi-currency
ledger is a product decision and out of scope.

## Frontend follow-up

Could switch to the new fields: `src/lib/admin/accounting/types.ts` (add `currency`, `EXPENSE`), `expense.ts` (record
rent as `type: 'EXPENSE'` instead of `ADJUSTMENT`, send an `Idempotency-Key` per form), `Statements.tsx` (its row key
workaround for the repeated `3200` can be dropped), `RecordExpense.tsx` (show `INVALID_AMOUNT` / `IDEMPOTENCY_KEY_REUSED`),
`CashFlow.tsx` (state that mobile money is not included), `Journal.tsx` (stop at row 10,000 as above).

## Date rules of the ranged routes (CAT-04, CAT-06)

`/journal-entries`, `/income-statement` and `/cash-flow` take required `startDate` and `endDate` (ISO `yyyy-MM-dd`) and share
one rule set:

| Input | Answer |
|---|---|
| a date that does not exist (`2026-02-30`, `2026-13-01`, `2026-04-31`) or is not ISO | `400` (strict parsing, before the handler runs) |
| a year outside 1..9999 (`+999999999-12-31`, `-0001-01-01`, `0000-12-31`, `+10000-01-01`) | `400`, `data.code = INVALID_DATE_RANGE` (was `409` on `/income-statement` and `/cash-flow`) |
| `startDate` after `endDate` | `400`, `data.code = INVALID_DATE_RANGE` (was `200` with an empty report on `/income-statement` and `/cash-flow`) |
| `0001-01-01` .. `9999-12-31`, or a single day (`startDate = endDate`) | accepted |

`/summary` and `/balance-sheet` are point-in-time snapshots and take **no dates**. Any query parameter, valid or not
(`?startDate=2026-02-30`), is ignored and the answer stays `200`: there is nothing to validate.

## GET /api/v1/admin/accounting/journal-entries (ADMIN): now paged

**Breaking change (CAT-03).** `data` used to be a bare JSON array of every entry in the date range (unbounded). It is
now a page in the same envelope as the admin order list (`GET /api/v1/order/all`, [orders.md](orders.md)). No Shopfi
frontend calls this route yet (checked on every frontend branch at the time of CAT-03), so nothing breaks today; a
client written against the old shape must read `data.content` and page.

| Query | Meaning |
|---|---|
| `startDate`, `endDate` | required, ISO dates (`2026-11-01`). **Both days are included whole**: entries with `startDate 00:00 <= transactionDate < (endDate + 1 day) 00:00` (server-local time, like `transactionDate`). Before CAT-03 the end was `endDate 23:59:59`, which missed entries in the last second's fractions. `startDate` after `endDate`: 400 `INVALID_DATE_RANGE` (before: an empty list). A single day (`startDate == endDate`) is fine. **CAT-04:** both dates must lie in the years 1..9999 (`0001-01-01` .. `9999-12-31`, both allowed); anything outside, e.g. `endDate=+999999999-12-31` (was a 500) or `startDate=-999999999-01-01` (was a 409), is 400 `INVALID_DATE_RANGE`. |
| `page`, `size` | `page` is 0-based. `size` defaults to **20** and is capped at **50** (larger values are clamped). A page may start at most at row 10,000: `page * size` (after the clamp) >= 10,000, or `page` > 1,000,000: 400 `INVALID_PAGE` (`data.maxOffset`, `data.size` on the offset error). Same rules and constants as the admin order list. Narrow the date range instead of paging deep. |
| `sort` | ignored. The order is fixed and stable: `transactionDate` newest first, then the entry id (newest first) as the tie-break, so walking the pages returns every entry exactly once even when several share a timestamp. |

```json
{
  "description": null,
  "data": {
    "content": [
      {
        "entryNumber": "JE-20330331-A1B2C3",
        "description": "Sale of order PB-20330331-7KQ3M8XW2D",
        "type": "SALE",
        "referenceType": "ORDER",
        "referenceId": "PB-20330331-7KQ3M8XW2D",
        "isManual": false,
        "recordedBy": null,
        "transactionDate": "2033-03-31T12:00:00",
        "lines": [
          { "accountCode": "1000", "accountName": "Cash", "entryType": "DEBIT", "amount": 100.00, "description": "..." },
          { "accountCode": "4000", "accountName": "Sales Revenue", "entryType": "CREDIT", "amount": 100.00, "description": "..." }
        ],
        "currency": "GHS"
      }
    ],
    "totalPages": 1, "totalElements": 1, "size": 20, "number": 1, "numberOfElements": 1,
    "isFirst": true, "isLast": true, "isEmpty": false
  }
}
```

The entry objects (`JournalEntryResponse`) are unchanged. The envelope is the shared `PageResponse`: as in every list
built by it, `number` is the **1-based** page number (`page + 1`), while the `page` query parameter is 0-based.

The page is built with a constant number of queries (one id page + one count + one load of those entries with their
lines and accounts), independent of the page size.

### Errors

| Status | `data.code` | When |
|---|---|---|
| 400 | `INVALID_DATE_RANGE` | `startDate` after `endDate`, or a date outside the years 1..9999 (CAT-04) |
| 400 | `INVALID_PAGE` | `page` > 1,000,000, or `page * size` >= 10,000 (`data.maxOffset`, `data.size`) |
| 400 | (no code) | `startDate` / `endDate` missing or not a date |

### Frontend follow-up

When an admin journal screen is built: request `?startDate=..&endDate=..&page=0&size=50`, render `data.content`, and
page with `data.totalPages` / `data.isLast`. Do not page past row 10,000; offer a narrower date range instead.

## Returned delivered orders (ORD-07)

`POST /api/v1/order/{orderNumber}/return` (see [orders.md](orders.md)) posts **one `SALE_REVERSAL` journal entry**
(reference type `ORDER`, the order number, description "Sale reversed for returned order: ...", `recordedBy` the admin's
e-mail), dated at the return, mirroring the order's `SALE` entry line by line: revenue, tax and discount lines are taken
back, the cash line becomes a **credit to 2100 Refunds Payable** (the money is still in the shop until refunded), and
COGS / inventory are reversed too because the goods are back on the shelf. The refund acknowledgement then posts the
`REFUND` entry (debit 2100, credit 1000 Cash and Bank) with the usual 409 `INSUFFICIENT_CASH` rule. The reversal reuses
the paid-cancel guards: each part (revenue, goods) is posted at most once per order, so a return can never double a
reversal and an order cannot be both cancelled and returned. Closed periods are not rewritten: sales analytics show the
reversal as negative revenue / COGS on the **return date**. Ledger balances move through the same single atomic
`UPDATE ... balance = balance + delta` as every other posting (accounts locked last, ascending id).

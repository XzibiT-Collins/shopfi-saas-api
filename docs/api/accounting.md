# Admin accounting API: journal entries (CAT-03)

Routes under `/api/v1/admin/accounting` are tenant scoped and `ADMIN` only (anonymous: 401, other roles: 403; see
[authorization-matrix.md](authorization-matrix.md)). Every response is `{ "description": string|null, "data": ... }`;
errors carry a stable machine code in `data.code`, never parse `description`.

This page documents only what CAT-03 (and the CAT-04 date bound) changed. The other accounting routes (`/summary`, `/income-statement`,
`/balance-sheet`, `/cash-flow`, `/journal-entries/reference`, `/journal-entries/manual`, `/metadata`) are unchanged.

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
        ]
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

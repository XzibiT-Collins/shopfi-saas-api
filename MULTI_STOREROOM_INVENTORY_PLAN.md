# Multi-Storeroom Inventory Location Tracking — Implementation Plan

## Context

A shop has multiple store rooms plus a shop floor, and sells both online (e-commerce) and in-store (walk-in). They need to:

1. Know **where each product physically is and how many units are at each location** at any time.
2. Keep an **auditable log of who moved what quantity, where→where, and when**.
3. **Alert staff when an item is empty on the shop floor but stock exists in a store room**, so it can be moved down.

Today inventory is **global** — there is no location concept. The source of truth is the FIFO cost-layer engine (`InventoryLayer` + `InventoryAllocation` + `InventoryMovement`, driven by `InventoryManagementServiceImpl`), with `Product.stockQuantity` as a denormalized global aggregate.

---

## Architecture Decision Records (ADR)

### ADR-001 — Lightweight location ledger over full per-location FIFO

**Status:** Accepted

**Context.** Two ways to add location awareness:
- **A — Full per-location FIFO:** make `location` a hard dimension on `InventoryLayer`/`InventoryAllocation`/`InventoryMovement` and rewrite the central `consumeInventory` engine. Single source of truth; enables true multi-location selling (optimal pick, split fulfilment, per-room oversell blocking). ~6–8 dev-days, large blast radius, risky data migration, touches every order/walk-in/adjustment/conversion path.
- **B — Lightweight ledger:** leave the FIFO cost/allocation/accounting machinery as the global source of truth; bolt on a separate physical-location ledger (per-location balances + transfer log) kept in sync by one hook in the consume engine. ~2 dev-days, small blast radius. Cost: dual source of truth (drift risk); sales deduct from a configured location rather than a physically-resolved one.

**Decision.** Choose **B**. The stated goal is *visibility, audit, and alerting* — "know where units are and who moved them" — not automated multi-location order routing. B delivers all three goals at a fraction of the risk and keeps the proven cost engine untouched.

**Consequences.** Accept drift risk (mitigated by a reconciliation flag), no auto location-pick / split / per-room oversell block. If true multi-location *selling* is needed later, this ledger is promoted to the source of truth and merged with the cost layers (a known, deliberate stepping stone).

### ADR-002 — Deduct location balance on SALE (finalize), not on reservation

**Status:** Accepted

**Context.** E-commerce lifecycle: checkout → *reserve* (cost layer drops now) → payment success → *finalize* (SALE) **or** fail/1h-expiry → *release* (cost layer restored). A reservation is a temporary hold; goods have not physically left the building until the order is paid/fulfilled.

**Decision.** The location ledger decrements **only on the SALE movement** (walk-in immediate consume + e-commerce finalize). Reserve and release paths do **not** touch location balances.

**Consequences.** Abandoned/expired carts never disturb location counts (correct). Between reserve and finalize, global stock is temporarily below sum-of-locations by the outstanding-reserved quantity — a **legitimate, expected delta**, not drift. Floor counts won't reflect pending online carts; documented and acceptable for a small shop.

### ADR-003 — Allow negative location balances (sell + alert), do not clamp or block

**Status:** Accepted

**Context.** When a sale's deducting location reads 0 but global stock exists (physical restock wasn't recorded as a transfer), options are: clamp at 0, allow negative, or block the sale.

**Decision.** **Allow the balance to go negative**, let the sale proceed (the cost engine already authorized it on global stock), and fire the restock alert.

**Consequences.** Negative is the honest "physical reality lagged here" signal and **preserves the invariant** `sum(locations) == global − outstanding-reserved`. Clamping would silently destroy the audit trail (the whole point of the feature); blocking would make sales depend on perfect location data, dragging B toward A's complexity. UI should render e.g. "−1 (needs restock)".

### ADR-004 — Configurable e-commerce fulfilment source, separate from walk-in floor

**Status:** Accepted

**Context.** The shop has two distinct sale points: walk-ins sell off the shop floor; online orders ship from a location that may differ (a back store room) and may change over time.

**Decision.** `StorageLocation` carries independent flags: `isWalkInSaleSource`, `isEcommerceFulfilmentSource`, `isDefaultReceiving`. The sync hook resolves the deduction location by sale type. Flags are DB-driven and admin-editable (no redeploy to repoint).

**Consequences.** At launch both sale flags point at the shop floor (matches ADR-006 backfill). To fulfil online from a store room, transfer stock there first, then flip the flag.

### ADR-005 — Transfers recorded by ADMIN + FRONT_DESK; location setup ADMIN-only

**Status:** Accepted

**Context.** Existing inventory endpoints are all ADMIN-only, but front-desk staff are the ones who physically move stock between rooms and the floor.

**Decision.** `POST /transfers` and transfer history allow `ADMIN` + `FRONT_DESK`. Location CRUD and default-flag changes stay `ADMIN`-only. `StockTransfer.movedBy` is a real `User` FK (not a string) to satisfy the "who moved what" audit requirement.

### ADR-006 — Backfill existing stock onto the shop floor

**Status:** Accepted

**Context.** Existing `Product.stockQuantity` has no location; go-live must assign it somewhere.

**Decision.** Seed all current stock onto the **shop floor** (the sale source). No day-one restock-alert storm, walk-in and online sales deduct cleanly. Staff redistribute surplus to store rooms via transfers afterward.

### ADR-007 — Inter-location transfer is not a financial event

**Status:** Accepted

**Context.** Transfers move physical units between rooms under the same ownership.

**Decision.** Transfers do **not** create journal entries; `BookkeepingService` is untouched. The cost engine's global total is unchanged by a transfer (useful invariant/assertion: total before == after).

---

## Deduction model (the core rule)

A single writer mutates location balances. The cost engine still authorizes every sale (on global stock); the ledger only records physical location.

| Flow | Cost engine (unchanged) | Location ledger (new) |
|------|------------------------|-----------------------|
| Receive / opening / adjustment-increase | layer `+` | `+` to default-receiving location; log `RECEIPT` |
| Walk-in sale | immediate consume, SALE | `−` from walk-in sale-source (shop floor); log `SALE_DEDUCTION` |
| E-com checkout / reserve | layer `−`, RESERVED | **untouched** |
| E-com payment success / finalize | RESERVED→CONSUMED, SALE | `−` from configured fulfilment-source; log `SALE_DEDUCTION` |
| E-com fail / 1h expiry / release | layer `+` restored | **untouched** |
| Adjustment-decrease | layer `−` | `−` from default-receiving (or sale-source); log `ADJUSTMENT` |
| Transfer (new) | **untouched** | `−` from source, `+` to dest; log `TRANSFER` |

Invariant: `SUM(LocationStock.quantityOnHand for product) == Product.stockQuantity − outstanding-reserved-qty`. Balances may be negative (ADR-003).

---

## New entities / enums

- **`enums/StorageLocationType.java`** — `STORE_ROOM`, `SHOP_FLOOR`.
- **`enums/StockTransferType.java`** — `TRANSFER`, `RECEIPT`, `SALE_DEDUCTION`, `ADJUSTMENT`.
- **`model/StorageLocation.java`** — `id`, `name` (unique), `type`, `active`, `lowStockThreshold` (Integer, nullable), flags `isDefaultReceiving` / `isWalkInSaleSource` / `isEcommerceFulfilmentSource`, audit dates + `@Version`. Entity style mirrors `InventoryLayer`. Service enforces "exactly one true per flag"; backed by Postgres partial unique indexes.
- **`model/LocationStock.java`** — balance row, unique `(product_id, location_id)`: `id`, `@ManyToOne product`, `@ManyToOne location`, `quantityOnHand` (Integer, default 0, may be negative), `@Version Long version`, audit dates.
- **`model/StockTransfer.java`** — append-only log shaped like `InventoryMovement`: `id`, `@ManyToOne product`, `@ManyToOne fromLocation` (nullable = receipt-in), `@ManyToOne toLocation` (nullable = sale-out), `quantity`, `@ManyToOne movedBy` (User, nullable for system paths), `transferType`, `note` (TEXT), `@CreatedDate createdAt`. Index `(product_id, created_at)`.

---

## The sync hook (only edit to existing inventory logic)

Create **`service/LocationLedgerSync.java`** (interface) + **`service/LocationLedgerSyncImpl.java`**. Single entry point:
`applyDelta(Product product, StorageLocation location, int delta, StockTransferType type, String note, User movedBy)` — the **only** code that mutates `LocationStock`. Upserts + locks the balance row, applies delta (allowing negative), appends a `StockTransfer`, and publishes `ShopFloorStockEvent` on a SHOP_FLOOR decrement.

Inject into **`InventoryManagementServiceImpl`** and call:
- end of `createLayer(...)` / receive → `+` to default-receiving;
- after `consumeInventory(...)` SALE path → `−`, location by reference type (walk-in → `isWalkInSaleSource`; e-com finalize → `isEcommerceFulfilmentSource`). **Reserve/release do NOT call the hook** (ADR-002);
- after adjustment paths → `±`.

`OrderServiceImpl` / `WalkInOrderServiceImpl` are **not modified** — the hook sits below them. Resolve `movedBy` via `AuthUserUtil.getCurrentUser()`; null + note for system/scheduled paths.

---

## Transfer operation

**`service/StockTransferService(+Impl)`** — `transfer(productId, fromLocationId, toLocationId, quantity, note)`, single `@Transactional`:
1. Validate `quantity > 0`, `from != to`, both active.
2. Load both balance rows; lock `from` with `@Lock(PESSIMISTIC_WRITE)`. Order locks by `location_id` to avoid deadlock.
3. Validate `from.quantityOnHand >= quantity` (transfers require real source stock, unlike sales) → else `BadRequestException`.
4. `from −= qty; to += qty;` save both atomically.
5. Append one `StockTransfer{from, to, TRANSFER, movedBy=currentUser, note}`.
6. Publish `ShopFloorStockEvent` if the floor is involved.

Concurrency: pessimistic write-lock on the hot sale-source / transfer-source row (concurrent walk-in + e-com finalize + transfer all hit the floor). `@Version` is the low-contention fallback.

---

## Out-of-stock-on-floor alert

Reuse the existing event + `NotificationService` + `findAllByRolesIn(ADMIN, FRONT_DESK)` + WebSocket pattern (mirror `notifyAdminsOfSuccessfulPayment` / `SuccessfulOrderHandler`).

- **`events/ShopFloorStockEvent.java`** — record `{ productId, productName }`, published after any SHOP_FLOOR decrement / transfer.
- **`handlers/ShopFloorStockHandler.java`** — `@Async @EventListener` (post-commit, own tx → **re-query**, don't reuse producer entities). If floor `quantityOnHand <= threshold` (location `lowStockThreshold`, fallback `Product.lowStockThreshold`) AND `SUM(store-room qty) > 0`, call `NotificationService.notifyStaffOfShopFloorShortage(...)`.
- **`config/ws/NotificationService.java`** — add `notifyStaffOfShopFloorShortage(...)`: persist `Notification{type="SHOP_FLOOR_RESTOCK", referenceType="PRODUCT", referenceId=productId}` + `NotificationRecipient` for ADMIN/FRONT_DESK, WebSocket-push. **Throttle:** skip if an unread `SHOP_FLOOR_RESTOCK` for that product already exists.

---

## API endpoints

All under `/api/v1/admin/inventory/...`, `CustomApiResponse<T>`, DTO records + Jakarta validation, Controller → interface → Impl.

- **`controller/StorageLocationController.java`** — `@PreAuthorize("hasRole('ADMIN')")`: `POST /locations`, `PUT /locations/{id}`, `GET /locations`, `GET /locations/{id}`, `PATCH /locations/{id}/defaults`.
- **`controller/StockTransferController.java`** — `@PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")`: `POST /transfers`, `GET /transfers?productId=&locationId=&page=`.
- **Extend `controller/InventoryManagementController.java`:** `GET /products/{productId}/stock-by-location` — per-location quantities + `balancesMatchGlobal` flag (treats outstanding reservations as tolerated delta).

---

## Migration / backfill — `V5__add_storeroom_inventory.sql`

(Migrations live in `src/main/resources/db/migrations/` — plural; confirm latest version prefix before naming.)

1. `CREATE TABLE storage_locations`, `location_stock` (`version BIGINT`, unique `(product_id, location_id)`), `stock_transfers` — V1 conventions (`BIGINT GENERATED BY DEFAULT AS IDENTITY`, `TIMESTAMP WITHOUT TIME ZONE`).
2. Partial unique indexes: `CREATE UNIQUE INDEX uq_default_receiving ON storage_locations(is_default_receiving) WHERE is_default_receiving;` — same for `is_walk_in_sale_source`, `is_ecommerce_fulfilment_source`.
3. Seed one `SHOP_FLOOR` (all three flags true at launch) and one `STORE_ROOM`.
4. Backfill onto floor: `INSERT INTO location_stock (...) SELECT p.id, <shop_floor_id via name subselect>, p.stock_quantity, 0, now(), now() FROM products p WHERE p.stock_quantity > 0;` (subselect id by name — no hardcoded ids).

---

## Critical files

**Create:**
- `model/StorageLocation.java`, `model/LocationStock.java`, `model/StockTransfer.java`
- `enums/StorageLocationType.java`, `enums/StockTransferType.java`
- `repository/{StorageLocationRepository,LocationStockRepository,StockTransferRepository}.java`
- `service/{LocationLedgerSync,LocationLedgerSyncImpl,StorageLocationService,StorageLocationServiceImpl,StockTransferService,StockTransferServiceImpl}.java`
- `controller/{StorageLocationController,StockTransferController}.java`
- `events/ShopFloorStockEvent.java`, `handlers/ShopFloorStockHandler.java`
- DTO records under `dto/inventory/...`
- `src/main/resources/db/migrations/V5__add_storeroom_inventory.sql`

**Modify:**
- `service/InventoryManagementServiceImpl.java` — inject `LocationLedgerSync`; one call after each `+`/`−` cost-layer mutation (only edit to existing inventory logic).
- `config/ws/NotificationService.java` — add `notifyStaffOfShopFloorShortage`.
- `controller/InventoryManagementController.java` — add stock-by-location endpoint.

---

## Verification

1. **Build/migrate:** run against dev DB; confirm `V5` applies, two locations seeded, floor backfilled from `Product.stockQuantity`, partial unique indexes reject a second default per flag.
2. **Receive stock:** `POST /receipts` → floor balance `+`, `StockTransfer{RECEIPT}` logged.
3. **Walk-in sale:** floor `−qty`, `SALE_DEDUCTION` logged, global `Product.stockQuantity` consistent.
4. **Allow-negative + alert:** floor 0, store room >0, process walk-in → floor negative, `SHOP_FLOOR_RESTOCK` notification fires to ADMIN/FRONT_DESK, throttled on a second sale.
5. **Transfer:** store room → floor → source `−`, dest `+`, `StockTransfer{TRANSFER, movedBy}` logged; over-quantity rejected; FRONT_DESK allowed, location CRUD denied for FRONT_DESK.
6. **E-commerce lifecycle:** reserve → balances unchanged; pay/finalize → fulfilment-source `−qty`; reservation expiry → balances untouched.
7. **Reconciliation:** `GET /products/{id}/stock-by-location` → `balancesMatchGlobal` true with no open reservations; reflects tolerated delta while a reservation is outstanding.
8. **Concurrency (optional):** concurrent walk-in + transfer on the same floor row → no lost update.

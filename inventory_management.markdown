# Inventory Management Decoupling Plan

## Summary
- Split inventory from product maintenance by introducing a dedicated inventory management flow for stock receipts and stock adjustments; product update becomes catalog-only and must no longer mutate `stockQuantity`, `costPrice`, or inventory-driven selling price.
- Adopt **FIFO inventory costing** with immutable inventory layers so existing stock keeps its original cost until exhausted; new cost only applies when the old layer has been fully consumed.
- Adopt **batch-effective selling price**: each receipt creates a layer with both `unitCost` and `unitSellingPrice`; the active product selling price is derived from the oldest available layer and only changes when that active layer is exhausted.
- Keep automatic accounting for receipts under **Inventory / Accounts Payable** and extend sale/adjustment accounting to use actual FIFO depletion costs rather than the mutable `Product.costPrice`.

## Key Changes
- Add an inventory domain with three core records:
  - `InventoryLayer`: per-product FIFO batch with `receivedQuantity`, `remainingQuantity`, `unitCost`, `unitSellingPrice`, `receivedAt`, `sourceType`, `sourceReference`, `createdBy`.
  - `InventoryMovement`: immutable audit trail for `RECEIPT`, `RESERVATION`, `RELEASE`, `SALE`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `CONVERSION_IN`, `CONVERSION_OUT`.
  - `InventoryAllocation`: reservation/consumption links from orders or walk-in sales to specific layers so reserved stock keeps its original batch cost and selling-price progression remains correct.
- Add a dedicated admin inventory service/controller, e.g. `/api/v1/admin/inventory`, with:
  - `POST /receipts` to receive stock with `productId`, `quantity`, `unitCost`, `unitSellingPrice`, `receivedAt`, `note/reference`.
  - `POST /adjustments` to post stock corrections. `ADJUSTMENT_OUT` consumes FIFO layers automatically; `ADJUSTMENT_IN` creates a new layer and requires `unitCost` plus `unitSellingPrice`.
  - `GET /products/{id}/summary` and `GET /products/{id}/history` for inventory snapshot, active layer, and movement history.
- Refactor product APIs:
  - Keep product create/update focused on catalog fields only.
  - For backward compatibility, product creation may still accept opening stock inputs, but implementation must delegate them into an opening inventory receipt instead of directly setting stock/cost.
  - Product update must reject inventory fields with validation errors so admin cannot silently revalue or restock through `/update/{productId}`.
- Refactor stock and pricing behavior:
  - `Product.stockQuantity` becomes a derived/cache field maintained from layer totals for fast reads.
  - `Product.price` becomes the current active catalog price derived from the oldest non-empty layer; when that layer reaches zero, promote the next layer price automatically.
  - `Product.costPrice` should no longer be treated as authoritative for valuation/COGS. Either remove it from business logic or repurpose it as the current active layer cost cache only.
- Refactor order and walk-in flows:
  - Online order reservation must allocate FIFO layer quantities at checkout; payment failure/cancel releases those allocations back to the same layers.
  - Sale finalization must consume existing allocations and stamp actual per-item cost from the allocated layers into `OrderItem` / `WalkInOrderItem`.
  - Walk-in sales can allocate and consume immediately in one transaction.
- Update stock conversion flow:
  - Reverse conversion (`BOX -> EA`, `DOZEN -> EA`, similar bulk-to-base flows) should inherit cost directly from the source layer being broken down. If one source bulk unit comes from one known layer, the resulting base-unit cost is derived deterministically from that source layer and conversion factor.
  - Forward conversion (`EA -> BOX`, similar base-to-bulk flows) must consume FIFO base-unit layers and derive target cost from the exact units consumed. This is the critical case because one target bulk unit may be assembled from multiple FIFO layers with different costs.
  - Source product depletion must consume FIFO layers.
  - When a conversion quantity spans multiple source layers, the service must split the depletion across each contributing layer and compute `fromCostValue` as the sum of those layer-level costs rather than a single blended or current product cost.
  - For forward conversion, the target product increase must create inventory that preserves the actual consumed cost composition. The implementation should create a target layer with the exact aggregated cost of the consumed source units and retain a conversion allocation trail back to the contributing source layers for auditability and variance analysis.
  - For reverse conversion, the target product increase should create one or more base-unit layers derived from the source bulk layer cost so the resulting unit costs remain traceable to the original bulk inventory consumed.
  - Accounting should keep current conversion journal logic, but amounts must come from actual layer values used in the conversion.

## Accounting / Interfaces
- New public request types:
  - `InventoryReceiptRequest(productId, quantity, unitCost, unitSellingPrice, receivedAt, note/reference)`
  - `InventoryAdjustmentRequest(productId, direction, quantity, reason, unitCost?, unitSellingPrice?, note/reference)`
  - Response DTOs for inventory summary, active layer, and movement history.
- Journal behavior:
  - Receipt: `Dr Inventory`, `Cr Accounts Payable` using `quantity * unitCost` from the receipt layer.
  - Adjustment out: `Dr Inventory Adjustment`, `Cr Inventory` using actual FIFO-depleted cost.
  - Adjustment in: `Dr Inventory`, `Cr Inventory Adjustment` using the explicit adjustment-in layer value.
  - Sale / walk-in sale: `Dr COGS`, `Cr Inventory` using actual consumed layer cost, not current product cost.
  - Stock conversion: journal amounts must be based on the exact source layers consumed and target layers created, including forward conversions assembled from multiple FIFO layers with different unit costs and reverse conversions broken down from a known bulk layer.
  - Remove family-wide inventory revaluation on ordinary cost updates; cost changes now arrive as new layers, not revaluation events.
- Existing bookkeeping service should gain inventory-specific entry methods keyed by receipt/allocation/movement identifiers rather than product-level mutable prices.

## Test Plan
- Receipt of new stock at a higher cost creates a new layer and does not change valuation of existing stock.
- FIFO sale after mixed-cost receipts consumes earlier layer first and records the correct COGS in order and walk-in journals.
- Active product selling price remains on the old layer price until that layer is exhausted, then switches to the next layer price.
- Online order reservation allocates layer quantities; failed payment releases the same allocations; successful payment consumes them.
- Adjustment out reduces inventory using FIFO layer costs and posts the correct journal entry.
- Adjustment in creates a new layer and updates product stock/active price correctly.
- Product update endpoint rejects stock/cost/inventory-driven selling-price edits.
- Stock conversion preserves total quantities and posts inventory values from layer-based depletion/increase.
- Stock conversion that consumes quantity across two or more FIFO layers with different costs calculates `fromCostValue`, target layer creation, and conversion variance from the exact layer split.
- Reverse conversion from a known bulk layer creates traceable base-unit layers with deterministic derived unit cost.
- Forward conversion from base units to bulk items computes bulk-item cost from the exact FIFO source units consumed, including cases where one bulk item is assembled from mixed-cost layers.
- Regression tests for low-stock reads, product listing stock values, and bookkeeping balance integrity.

## Assumptions
- FIFO is the costing policy for all inventory depletion.
- Selling price is batch-effective and follows the oldest available layer, not the latest receipt immediately.
- Accounts payable remains the default offset account for stock receipts in v1.
- Forward conversion is the more complex pricing path and must never rely on a single cached `Product.costPrice`; reverse conversion may derive cost directly from the consumed bulk layer.
- Product creation can remain backward-compatible only if opening stock is internally translated into an opening receipt; no direct stock mutation should remain in product services.
- Supplier management, purchase-order workflows, and advanced inventory reporting can stay out of scope for this first pass unless later required.

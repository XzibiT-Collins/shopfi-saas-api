# Inventory Management Implementation Notes

## Overview

Audience: engineers and future maintainers working on product catalog, stock flows, bookkeeping, checkout, walk-in orders, and stock conversion.

This document explains the current inventory-management implementation that was introduced to solve a core accounting problem:

- product update used to change `stockQuantity` and `costPrice` directly
- a new receipt at a different cost could overwrite the cost basis of older stock
- profit and COGS calculations could become wrong because older inventory lost its original cost identity

The current design moves inventory into a dedicated FIFO-based flow. Stock now has memory. Each receipt creates a costed layer, and sales, adjustments, and conversions consume those layers instead of relying on one mutable product cost.

This is an internal feature note, not an API guide for end users.

## Problem And Intent

The implementation is designed around these business rules:

- stock changes must not happen through normal product update
- cost price for existing stock must remain historically correct
- new stock can arrive with a different cost price and selling price
- the point where the new cost/selling price becomes active must be deterministic
- accounting entries must reflect the actual inventory value moved, not a cached catalog value

The chosen rules in the current implementation are:

- inventory depletion uses **FIFO**
- selling price is **batch-effective**
- product create may still accept opening stock, but that stock is translated into inventory layers
- product update is now catalog-focused and rejects stock/cost changes

## Architecture Summary

The main implementation centers on these concepts:

- `InventoryLayer`
  - table: `inventory_layers`
  - represents one FIFO batch for a product
  - stores `receivedQuantity`, `remainingQuantity`, `unitCost`, `unitSellingPrice`, `sourceType`, `sourceReference`, `receivedAt`

- `InventoryMovement`
  - table: `inventory_movements`
  - immutable audit trail of what happened to inventory
  - movement types currently include receipt, reservation, release, sale, adjustment in/out, conversion in/out, and legacy bootstrap

- `InventoryAllocation`
  - table: `inventory_allocations`
  - used mainly for online checkout reservation
  - links reserved stock to the exact FIFO layer it came from
  - lets payment success consume the same reserved stock later without recalculating cost

- `InventoryManagementService`
  - service boundary for receiving stock, adjusting stock, reserving stock, releasing reservations, consuming inventory, and creating conversion layers

- `Product`
  - still keeps `stockQuantity`, `price`, and `costPrice`, but these now act as the **current active cache**
  - they are refreshed from the oldest non-empty FIFO layer
  - they are no longer the source of truth for inventory accounting

## Core Files

Primary implementation files:

- `src/main/java/com/example/perfume_budget/service/InventoryManagementServiceImpl.java`
- `src/main/java/com/example/perfume_budget/service/interfaces/InventoryManagementService.java`
- `src/main/java/com/example/perfume_budget/controller/InventoryManagementController.java`
- `src/main/java/com/example/perfume_budget/model/InventoryLayer.java`
- `src/main/java/com/example/perfume_budget/model/InventoryMovement.java`
- `src/main/java/com/example/perfume_budget/model/InventoryAllocation.java`

Key integration files:

- `src/main/java/com/example/perfume_budget/service/ProductServiceImpl.java`
- `src/main/java/com/example/perfume_budget/service/OrderServiceImpl.java`
- `src/main/java/com/example/perfume_budget/service/WebhookProcessorImpl.java`
- `src/main/java/com/example/perfume_budget/service/WalkInOrderServiceImpl.java`
- `src/main/java/com/example/perfume_budget/service/StockConversionServiceImpl.java`
- `src/main/java/com/example/perfume_budget/service/BookkeepingService.java`

## Data Model And Meaning

### Inventory Layers

Each layer is one batch of inventory for one product.

Important fields:

- `receivedQuantity`: original quantity received into the batch
- `remainingQuantity`: quantity still available for future FIFO consumption
- `unitCost`: cost basis of this exact batch
- `unitSellingPrice`: selling price that becomes active when this layer is the oldest available stock
- `sourceType`: where the batch came from
  - `OPENING_STOCK`
  - `PURCHASE`
  - `ADJUSTMENT_IN`
  - `CONVERSION_IN`
  - `LEGACY_BALANCE`
- `sourceReference`: human/audit reference for the batch

### Inventory Movements

Movements are the audit log. They exist so the system can answer:

- what happened
- when it happened
- which layer was involved
- which business reference caused it

This is the durable trace for receipts, reservations, releases, sales, adjustments, and conversions.

### Inventory Allocations

Allocations are only needed when inventory is reserved first and consumed later.

Current use:

- online order checkout reserves stock before payment is completed
- on payment success, reserved allocations are marked consumed
- on payment failure, reserved allocations are released back to the same FIFO layers

This prevents payment timing from changing cost basis.

## Current FIFO Rules

### Receipt Rule

Whenever stock is received, the system creates a new `InventoryLayer`.

The layer stores:

- quantity received
- unit cost
- unit selling price
- source metadata

The system then refreshes the product cache:

- `Product.stockQuantity` becomes the sum of all remaining layer quantities
- `Product.price` becomes the selling price of the oldest non-empty layer
- `Product.costPrice` becomes the cost of the oldest non-empty layer

### Consumption Rule

Whenever stock is consumed, the service reads layers ordered by:

1. `receivedAt`
2. `id`

It then consumes from the oldest layer first until the required quantity is satisfied.

This means:

- old stock keeps its cost identity until fully exhausted
- mixed-cost consumption is represented as several layer-level consumptions
- total cost is the sum of each contributing layer segment

### Selling Price Activation Rule

Selling price follows the oldest non-empty layer.

Example:

- Layer 1: 10 units at cost `GHS 10`, selling `GHS 18`
- Layer 2: 5 units at cost `GHS 12`, selling `GHS 20`

As long as Layer 1 still has remaining stock:

- active `Product.costPrice` = `GHS 10`
- active `Product.price` = `GHS 18`

When Layer 1 reaches zero:

- Layer 2 becomes active
- product cache moves to `GHS 12` cost and `GHS 20` selling price

## Product Flow Changes

### Product Creation

`ProductServiceImpl.createProduct(...)` defines the product *type* only; it does not accept or record stock. Stock enters exclusively through the receive endpoint.

Current behavior:

- product is created with `stockQuantity = 0`
- base products still get their initial cost/selling price set so a product record can exist (these are overwritten from the active layer once stock is received)
- no inventory layer or movement is created at product creation

Opening stock is recorded on the **first stock receipt** for a product. In `InventoryManagementServiceImpl.receiveStock(...)`, when a product has no inventory layers and zero stock, the receipt's layer is tagged `OPENING_STOCK` (otherwise `PURCHASE`). Bookkeeping records the inventory value from that receipt as usual.

### Product Update

`ProductServiceImpl.updateProduct(...)` now enforces inventory ownership rules.

It rejects:

- direct stock changes
- direct cost-price changes
- selling-price changes while the product still has stock on hand

The intention is:

- catalog fields may still change normally
- inventory-sensitive fields must go through inventory management

This is one of the most important guardrails in the implementation.

## Admin Inventory APIs

Controller:

- `src/main/java/com/example/perfume_budget/controller/InventoryManagementController.java`

Endpoints:

- `POST /api/v1/admin/inventory/receipts`
  - records a new inventory receipt
  - requires `productId`, `quantity`, `unitCost`, `unitSellingPrice`, `reference`

- `POST /api/v1/admin/inventory/adjustments`
  - records either an increase or decrease
  - increase requires `unitCost` and `unitSellingPrice`
  - decrease uses FIFO depletion and actual consumed cost

- `GET /api/v1/admin/inventory/products/{productId}/summary`
  - returns current stock summary and visible layers

- `GET /api/v1/admin/inventory/products/{productId}/history`
  - returns movement history

All endpoints are admin-only.

## Online Order Flow

### Reservation At Checkout

`OrderServiceImpl.checkout(...)` now calls:

- `reserveStock(orderNumber, orderItems)`

which delegates to:

- `inventoryManagementService.reserveOrderInventory(...)`

That service:

- consumes FIFO layer quantity immediately from `remainingQuantity`
- creates `InventoryAllocation` records with status `RESERVED`
- records `InventoryMovement` rows of type `RESERVATION`
- refreshes product stock cache

The important nuance is that inventory is effectively removed from available stock at reservation time, not at payment success time.

### Payment Failure

`WebhookProcessorImpl.handlePaymentFailure(...)` now calls:

- `orderService.releaseStock(orderNumber)`

which delegates to:

- `inventoryManagementService.releaseOrderInventory(orderNumber)`

That service:

- restores quantity back to the original reserved layers
- marks allocations as `RELEASED`
- records `RELEASE` movements
- refreshes product cache

This preserves the exact FIFO origin of the reserved stock.

### Payment Success

`WebhookProcessorImpl.handlePaymentSuccess(...)` now calls:

- `orderService.finalizeReservedStock(order)`

which delegates to:

- `inventoryManagementService.finalizeReservedOrder(order)`

That service:

- finds `RESERVED` allocations for the order
- marks them `CONSUMED`
- records `SALE` movements
- calculates actual total cost per product line from the reserved layer segments
- stamps the resolved average unit cost back into `OrderItem.costPrice`

Only after that does `BookkeepingService.recordSale(...)` run, so the sale journal uses the real FIFO-derived order item cost.

## Walk-In Order Flow

Walk-in orders do not reserve first and settle later. They consume immediately.

`WalkInOrderServiceImpl.placeWalkInOrder(...)` now:

- validates product availability against current cached stock
- builds order items with placeholder cost
- calls `inventoryManagementService.consumeWalkInInventory(orderNumber, items)`
- that service consumes FIFO layers immediately and stamps actual cost back into each `WalkInOrderItem`
- sold count is incremented after inventory consumption
- `BookkeepingService.recordWalkInSale(...)` then uses the actual consumed cost

This makes walk-in cost accounting consistent with online orders.

## Stock Adjustment Flow

### Adjustment Increase

For an increase:

- a new layer is created
- the increase behaves like a stock introduction event
- the increase must include `unitCost` and `unitSellingPrice`
- accounting entry:
  - debit inventory
  - credit inventory adjustment

### Adjustment Decrease

For a decrease:

- FIFO layers are consumed
- no new layer is created
- total adjustment value is computed from the exact layer costs consumed
- accounting entry:
  - debit inventory adjustment
  - credit inventory

This is important because a decrease must not use a cached product cost if multiple layers exist.

## Stock Conversion Flow

This is the part most likely to become confusing later.

### Naming In Current Code

Current code uses these names:

- `convertForward(...)`: non-base unit to base unit
- `convertReverse(...)`: base unit to non-base unit

So "forward" and "reverse" are tied to the existing SKU conversion API, not to accounting intuition.

### Forward Conversion In Current Code

Current meaning:

- source product is a non-base unit like `BOX`
- target product is the base unit like `EA`
- quantity added to target is `sourceQuantity * conversionFactor`

Cost handling:

- source bulk inventory is consumed using FIFO layers
- if multiple bulk layers are involved, consumption is split across those source layers
- target inventory is then created from those consumed source segments

Current implementation detail:

- one target conversion layer is created per consumed source layer segment
- each created target layer uses a derived unit cost from the source segment

This keeps mixed-cost source depletion traceable on the target side.

### Reverse Conversion In Current Code

Current meaning:

- source product is the base unit like `EA`
- target product is a bulk unit like `BOX`
- source quantity must divide evenly by the target conversion factor

Cost handling:

- source base-unit inventory is consumed using FIFO
- total cost of consumed source units is summed
- target bulk quantity is created with a derived unit cost from the consumed value

This is simpler conceptually when the consumed source has a single cost layer, but the implementation also supports mixed source layers because total source cost is aggregated from actual FIFO consumption.

### Why Conversion Needed Layer Logic

This is the core reason conversion had to change.

Example:

- existing EA stock:
  - 10 units at `GHS 10`
  - 2 units at `GHS 12`
- convert 12 EA into 1 BOX

The box cost must become:

- `10 * 10 + 2 * 12 = GHS 124`

It must not become:

- `12 * current Product.costPrice`

because that would erase the mixed-layer reality.

### Conversion Accounting

`BookkeepingService.recordStockConversion(...)` still posts the conversion journal, but the values it receives now come from actual consumed inventory values instead of a cached product cost.

The intended rule is:

- `fromCostValue` must reflect the exact FIFO layers consumed
- `toCostValue` must reflect the actual inventory value created on the target side
- variance should only represent real value difference, not a costing shortcut

## Bookkeeping Changes

The bookkeeping service now supports:

- `recordInventoryPurchase(product, quantity, inventoryValue)`
- `recordInventoryAdjustmentIn(product, quantity, adjustmentValue)`
- existing reduction and sale methods now indirectly benefit because order and walk-in items carry resolved FIFO-based cost before journaling

Important accounting behavior:

- receipt: inventory debit, accounts payable credit
- adjustment decrease: inventory adjustment debit, inventory credit
- adjustment increase: inventory debit, inventory adjustment credit
- sale: COGS debit, inventory credit using order item cost resolved from FIFO allocations
- walk-in sale: same principle using walk-in item cost resolved from FIFO consumption

## Legacy Bootstrap Behavior

This was added to avoid breaking existing data.

If a product has:

- positive `stockQuantity`
- but no `InventoryLayer` rows yet

the inventory service will create a synthetic `LEGACY_BALANCE` layer on first inventory touch.

This happens in:

- inventory summary lookup
- inventory history lookup
- any inventory consumption path

The bootstrap layer uses:

- current `Product.stockQuantity`
- current `Product.costPrice`
- current `Product.price`

This is a compatibility bridge for pre-layer products. It is useful, but it also means older data is backfilled from the cached product state, not from historical receipts that never existed.

## Important Defaults And Constraints

These are the assumptions baked into the current implementation:

- FIFO is the only depletion rule
- product cache values are derived from the oldest non-empty layer
- selling price for stocked products cannot be changed through normal product update
- online orders reserve first, then consume on successful payment
- walk-in orders consume immediately
- inventory increases require explicit `unitCost` and `unitSellingPrice`
- conversions depend on actual consumed layer values

## Operational Verification

When revisiting or extending this later, verify these paths first:

1. Receive stock with a new cost and new selling price.
2. Confirm inventory summary shows:
   - total stock
   - active product cache still tied to the oldest non-empty layer
3. Place an online order and confirm:
   - stock decreases immediately after reservation
   - allocations are created
4. Fail the payment and confirm:
   - stock returns to the same layers
5. Complete a payment and confirm:
   - allocations become consumed
   - `OrderItem.costPrice` is populated from FIFO
6. Place a walk-in order and confirm:
   - `WalkInOrderItem.costPrice` reflects FIFO
7. Run a conversion across mixed-cost source layers and confirm:
   - source depletion follows FIFO
   - target inventory value reflects exact consumed cost
   - journal values use the same cost basis

## Known Gaps And Caveats

These are important to remember because they can surprise you later.

- No checked-in SQL migrations were added here. This project currently relies on Hibernate schema update behavior.
- Product update blocks selling-price changes whenever stock exists. That protects batch-effective pricing, but it also means catalog repricing now requires inventory management or stock exhaustion.
- `Product.stockQuantity`, `Product.price`, and `Product.costPrice` are still stored on the product. They are cache/active-layer values, not authoritative historical truth.
- The legacy bootstrap layer is only as accurate as the old cached product values. It cannot reconstruct true historical receipts.
- `InventoryAllocation.referenceLineKey` currently uses `product:<productId>`. If the same product can appear more than once as separate order lines in the same order, this key is not line-unique and should be improved.
- Conversion naming is inherited from existing API behavior and may feel backwards at first:
  - forward = non-base to base
  - reverse = base to non-base
- Existing `BookkeepingService.recordInventoryRevaluation(...)` still exists in code, but ordinary inventory cost changes should no longer rely on that path because new cost now enters as a new layer, not as a global product revaluation.
- This documentation is based on the current code paths. If the flow changes later, update this document together with the implementation.

## Suggested Future Cleanup

If you continue this work later, these are sensible next steps:

- add explicit DB migrations for the new inventory tables
- make allocation line keys truly order-line specific
- expose richer inventory history and layer drill-down in admin UI
- document example request payloads for the new inventory endpoints
- decide whether product selling-price updates should get a dedicated admin receipt/repricing workflow instead of being blocked on stocked products
- review whether conversion target-layer creation should remain aggregated in some cases or always preserve one-to-one source-layer traceability

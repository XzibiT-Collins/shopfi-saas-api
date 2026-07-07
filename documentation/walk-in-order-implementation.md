# Walk-In Order Implementation

## Overview

Audience: engineers and maintainers working on admin order flows, POS behavior, inventory mutation, and bookkeeping.

This feature adds a dedicated walk-in order flow for in-store sales handled by admins. A walk-in order bypasses the cart, payment gateway, and webhook lifecycle entirely. The order is created synchronously, stock is deducted immediately, the order is marked completed at creation time, and bookkeeping is recorded against the actual walk-in payment method.

## Problem And Intent

The application already supported online checkout through the cart and Paystack. That flow is not a fit for point-of-sale transactions inside a physical store, where:

- an admin creates the order on behalf of the customer
- the customer may be a registered user, an identified walk-in customer, or anonymous
- payment is collected immediately in person
- stock must be reduced in the same transaction as the sale
- the accounting entry must reflect whether payment was received as cash, mobile money, card, or a cash/mobile split

## Implementation Summary

The main feature behavior is implemented across:

- `WalkInOrderServiceImpl`: customer resolution, item building, manual discount application, tax calculation, payment validation, stock deduction, order persistence, and bookkeeping handoff
- `WalkInOrderController`: admin-only walk-in order endpoints
- `BookkeepingService.recordWalkInSale(...)`: journal creation for walk-in sales with payment-method-specific debit lines
- `WalkInOrderMapper`: response shaping and customer/payment display formatting
- `WalkInOrderNumberGenerator`: `WLK-*` order number generation with uniqueness checks

The walk-in domain added by this feature includes:

- `WalkInCustomer`
- `WalkInOrder`
- `WalkInOrderItem`
- `WalkInOrderTax`

The admin endpoints exposed by this feature are:

- `POST /api/v1/admin/walk-in/order`
- `GET /api/v1/admin/walk-in/customers/search`
- `GET /api/v1/admin/walk-in/orders`
- `GET /api/v1/admin/walk-in/orders/{orderNumber}`
- `PATCH /api/v1/admin/walk-in/orders/{orderNumber}/receipt-printed`

These endpoints are protected with `@PreAuthorize("hasRole('ADMIN')")`.

## Key Behaviors And Edge Cases

### Customer Resolution

- If `registeredUserId` is provided, the order is linked to an existing `User` with the `CUSTOMER` role.
- If a walk-in customer payload is provided, a `WalkInCustomer` record is created and linked to the order.
- If neither is provided, the service creates an anonymous `WalkInCustomer` with `isAnonymous=true`.
- Response mapping resolves customer display values in this order:
  - registered user full name and profile phone
  - walk-in customer name and phone
  - fallback values of `Anonymous` and `N/A`

### Order Creation Rules

- Walk-in orders never use the `Cart` entity.
- Walk-in orders are created directly with `WalkInOrderStatus.COMPLETED`.
- The order number format is `WLK-{yyyyMMddHHmmss}-{8 char uppercase suffix}`.
- The generator checks repository uniqueness and retries up to 5 times before failing.
- Receipt state starts as `false` and can be flipped through the receipt-printed endpoint.

### Item Validation And Stock Mutation

- Every requested product is loaded directly from `ProductRepository`.
- Products must exist and be active.
- Requested quantity must not exceed current stock.
- The service snapshots `productId`, `productName`, `sku`, `unitPrice`, `costPrice`, and computed `totalPrice` into `WalkInOrderItem`.
- Stock deduction and `soldCount` increment happen synchronously before the order transaction completes.

### Discount And Tax Handling

- Walk-in orders support direct admin-entered discounts and do not use coupons.
- Discount input is optional and uses the existing `DiscountType` enum:
  - `PERCENTAGE`
  - `FLAT`
- `discountType` and `discountValue` must be provided together.
- Percentage discounts cannot exceed `100`.
- Flat discounts are capped at the subtotal so the discounted base never goes below zero.
- The selected discount metadata is stored on the walk-in order for audit and response display.
- Taxes are calculated through the existing `TaxService`.
- Taxes are calculated after discount has been applied.
- Walk-in tax rows are persisted as raw `BigDecimal` values in `WalkInOrderTax`.
- The system still uses `Money` for order-level monetary totals.

### Payment Validation

- Supported payment methods are `CASH`, `MOBILE_MONEY`, `CARD`, and `SPLIT`.
- `CASH` requires `amountPaid >= totalAmount`.
- `MOBILE_MONEY` and `CARD` require `amountPaid == totalAmount`.
- `SPLIT` is currently strict:
  - `splitCashAmount + splitMobileAmount` must equal `totalAmount`
  - `amountPaid` must equal the combined split amount
- `changeGiven` is zero for `MOBILE_MONEY` and `CARD`.
- `changeGiven` is also zero for `SPLIT` because overpayment is intentionally not allowed in the current implementation.

## Bookkeeping And Ledger Impact

This feature adds:

- `AccountCategory.MOBILE_MONEY`
- `JournalEntryType.WALK_IN_SALE`
- a seeded ledger account: code `1001`, name `Mobile Money`, type `ASSET`

`BookkeepingService.recordWalkInSale(...)` follows the existing online-sale discount treatment rather than introducing a separate accounting policy for walk-in discounts.

Walk-in journal entries record:

- DEBIT `CASH` for full total on `CASH`
- DEBIT `MOBILE_MONEY` for full total on `MOBILE_MONEY`
- DEBIT `CASH` for full total on `CARD`
- DEBIT `CASH` and DEBIT `MOBILE_MONEY` separately for `SPLIT`
- CREDIT `SALES_REVENUE` for subtotal
- CREDIT `TAX_PAYABLE` when tax is greater than zero
- DEBIT `DISCOUNT_EXPENSE` when discount is greater than zero
- DEBIT `COGS` and CREDIT `INVENTORY` for total item cost

The journal entry also records:

- type `WALK_IN_SALE`
- reference type `WALK_IN_ORDER`
- reference id equal to the walk-in order number
- description including the order number, admin name, and payment method

## Flags, Config, Or Migrations

- No feature flag or toggle was found for this feature.
- The implementation relies on the project’s current schema-management approach rather than checked-in SQL migrations.
- The `dev` profile continues to use Hibernate schema update behavior.

Environments that do not auto-apply schema changes will need corresponding database changes for:

- `walk_in_customers`
- `walk_in_orders`
- `walk_in_order_items`
- `walk_in_order_taxes`
- the new ledger account category and seeded account

## Testing And Verification

Automated verification completed with:

```bash
mvn -DskipTests compile
mvn "-Dtest=WalkInOrderServiceImplTest,WalkInBookkeepingServiceTest" test
```

The added test coverage includes:

- `WalkInOrderServiceImplTest`
- `WalkInBookkeepingServiceTest`

Behavior verified by tests includes:

- successful anonymous cash walk-in order creation
- successful percentage-discount walk-in order creation
- successful flat-discount walk-in order creation with subtotal cap
- rejection of incomplete discount payloads
- successful registered-customer split payment flow
- rejection of insufficient cash payment
- customer search mapping
- paginated walk-in order retrieval
- bookkeeping entries for mobile money sales
- bookkeeping entries for split cash/mobile-money sales

## Known Gaps

- There is no dedicated refund or cancellation workflow for walk-in orders yet, even though the status enum includes `CANCELLED` and `REFUNDED`.
- `CARD` payments are still journaled to the `CASH` asset account for simplicity.
- Split overpayment and change handling are intentionally not supported in this iteration.
- Security for the new routes is enforced at the controller method level. No new path matcher was added to `SecurityConfig` because the existing project pattern already uses authenticated-by-default plus `@PreAuthorize`.

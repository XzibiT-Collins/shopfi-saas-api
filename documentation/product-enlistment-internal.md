# Product Enlistment Implementation Notes

## Overview

Audience: engineers and maintainers working on product management, ecommerce visibility, cart, checkout, and walk-in sales.

This document explains the backend `isEnlisted` product flag and the behavioral split it introduces between ecommerce-visible products and products that only exist for admin or walk-in use.

The main use case is that some products should remain available for inventory management and walk-in sales without being listed for customers in the ecommerce storefront.

## Problem And Intent

Before this change, customer-facing product visibility was effectively tied to `isActive`, and not all public product read paths enforced that consistently.

That model was too coarse for this business need:

- some products should be active in the system
- those products may still be stocked, converted, and sold as walk-in orders
- but they should not appear in ecommerce listing, search, details, cart, or checkout

The new `isEnlisted` flag separates those concerns.

## Key Rule

For ecommerce visibility and online purchase eligibility, a product must satisfy both:

- `isActive == true`
- `isEnlisted == true`

This rule now applies to:

- public product listing
- public product search
- public product details by slug
- add-to-cart
- checkout validation

`isActive` alone is no longer enough for ecommerce.

## Data Model Change

`Product` now includes:

- `isEnlisted`

Current default in code:

- `false`

This means new product creation should set the flag explicitly if the product is meant to appear in ecommerce.

## Implementation Summary

### Product Visibility

Customer-facing product reads were updated so ecommerce access requires both flags.

Current enforcement points:

- public listing repository query
- public search specification
- public product details slug lookup

Admin product listing and admin product search remain intentionally broad so admins can still manage unenlisted products.

### Cart And Checkout Protection

Customer purchase flows now enforce ecommerce eligibility directly.

Current protection points:

- `CartItemServiceImpl.addItemToCart(...)`
- `CartItemServiceImpl.updateCartItem(...)`
- `OrderServiceImpl.checkout(...)`

This is important because frontend state can become stale. Even if a product was listed when a customer first saw it, checkout must still fail safely if the product becomes inactive or unenlisted later.

### Walk-In And Admin Flows

Walk-in and admin operational flows were intentionally not restricted by `isEnlisted`.

That means unenlisted products can still be used in:

- walk-in order placement
- admin inventory management
- stock conversion
- admin product management

This is expected behavior, not an omission.

## Image Validation Behavior

Image validation was adjusted to follow ecommerce intent more closely.

Current rule:

- image is required when a product is both active and enlisted for ecommerce

This avoids forcing walk-in-only products through ecommerce-style media requirements.

## API And DTO Impact

The admin product DTOs now include `isEnlisted`.

Affected request/response models:

- `ProductRequest`
- `ProductDetails`
- `ProductListing`

Frontend admin product forms and admin product views should use this field explicitly.

## Operational Meaning

Think of the flags this way:

- `isActive`
  - product is active in the system
- `isEnlisted`
  - product is listed for ecommerce

Examples:

- active + enlisted
  - visible to customers and purchasable online
- active + not enlisted
  - available for admin and walk-in use, hidden from ecommerce
- inactive + enlisted
  - still blocked from ecommerce because both flags are required
- inactive + not enlisted
  - fully non-ecommerce and inactive

## Testing Coverage Added

The implementation includes test updates around:

- mapping `isEnlisted` in product responses
- creating products with `isEnlisted = false`
- updating enlistment state
- blocking public slug access for unenlisted products
- blocking add-to-cart for unenlisted products
- blocking checkout for unenlisted products

## Known Caveat

Because `isEnlisted` is not defaulted to `true`, products created without this field being set intentionally may end up hidden from ecommerce.

That is consistent with the requested backend behavior, but the frontend admin form should make the field explicit so this does not happen accidentally.

## Suggested Verification

1. Create a product with `isActive = true` and `isEnlisted = false`.
2. Confirm it appears in admin product management.
3. Confirm it does not appear in public listing or search.
4. Confirm direct public slug access does not resolve.
5. Confirm add-to-cart fails for that product.
6. Confirm walk-in order flow can still use that product.
7. Update the same product to `isEnlisted = true`.
8. Confirm it becomes visible in ecommerce again.

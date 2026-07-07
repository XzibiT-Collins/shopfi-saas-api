# Complex SKU Stock Conversion

## Overview

Audience: engineers and maintainers working on product catalog, inventory, and admin APIs.

This feature adds product-family based SKU handling and stock conversion workflows so inventory can move between bulk variants and a family base unit in both directions. The implementation now follows the project flow rules more closely: controllers are transport-only, services own business validation and orchestration, and code generators live in a dedicated utility.

## Problem And Intent

The catalog now supports multiple SKUs inside one product family, where each variant is identified by:

- a shared family code
- a unit of measure such as `EA`, `BOX`, `PACK`, `DOZEN`, or `PAIR`
- a conversion factor relative to the family base unit

The stock conversion workflow is intended to support:

- forward conversion from a bulk SKU into the base unit
- reverse conversion from the base unit back into a bulk SKU
- inventory variance capture when cost values do not balance exactly

## Implementation Summary

The main feature behavior is implemented across:

- `ProductServiceImpl`: family creation, variant creation, SKU generation, conversion-factor handling, and auto-calculated variant cost price
- `StockConversionServiceImpl`: forward and reverse stock conversion rules, stock mutation, conversion record persistence, and bookkeeping handoff
- `ProductFamilyServiceImpl`: family listing and available-UOM lookup for admin flows
- `ProductCodeGenerator`: family code and conversion number generation

The admin endpoints exposed by this feature are:

- `GET /api/v1/admin/product-families`
- `GET /api/v1/admin/product-families/{id}/available-uoms`
- `POST /api/v1/admin/stock-conversions/forward`
- `POST /api/v1/admin/stock-conversions/reverse`

These endpoints are protected with `@PreAuthorize("hasRole('ADMIN')")`.

The refactor also changed the endpoint boundary so controllers no longer return JPA entities directly. They now return explicit response DTOs for:

- product family summaries
- available UOM lookups
- stock conversion results

## Key Behaviors And Edge Cases

### SKU And Family Rules

- Creating a new product with `isNewProduct=true` creates a new product family and assigns the new product as the base `EA` unit.
- The base unit always uses conversion factor `1` and SKU format `<familyCode>-EA`.
- Creating a new variant for an existing family requires a `familyId` and a UOM code.
- Variant SKU format is `<familyCode>-<uomCode>`.
- Duplicate SKUs for the same family are rejected.
- Variant cost price is auto-calculated from `baseUnit.costPrice * conversionFactor`.
- `DOZEN` and `PAIR` currently override the supplied conversion factor to `12` and `2` respectively.

### Forward Conversion

- Source product must be a non-base unit.
- Source stock must be sufficient.
- The product family must have a configured base unit.
- Quantity added to the target base unit is `sourceQuantity * sourceProduct.conversionFactor`.

### Reverse Conversion

- Source product must be the base unit.
- `targetProductId` is required.
- Source and target products must belong to the same family.
- Source stock must be sufficient.
- Source quantity must be divisible by the target conversion factor.
- Quantity added to the target bulk SKU is `sourceQuantity / targetProduct.conversionFactor`.

### Variance And Bookkeeping

- Both conversion directions compute:
  - `fromCostValue`
  - `toCostValue`
  - `varianceAmount = fromCostValue - toCostValue`
- Every successful conversion writes a `StockConversion` record and then calls `BookkeepingService.recordStockConversion(...)`.

## Flags, Config, Or Migrations

- No feature flag or toggle was found for this feature.
- The `dev` profile uses `spring.jpa.hibernate.ddl-auto=update`.
- This project intentionally relies on Hibernate schema updates instead of checked-in SQL migration files for these changes.

Schema changes for `product_families`, `units_of_measure`, and `stock_conversions` are expected to be applied by Hibernate in environments that use the same schema-management approach.

## Testing And Verification

Automated verification completed with:

```bash
mvn -q test
```

The updated test coverage includes:

- `ProductServiceImplTest`
- `StockConversionServiceImplTest`
- `ProductFamilyServiceImplTest`
- `ProductMapperTest`
- `ProductCodeGeneratorTest`

Behavior verified by tests includes:

- new family creation and base-unit SKU assignment
- variant SKU and cost calculation
- forward conversion success and stock deduction/addition
- reverse conversion success, variance handling, and invalid quantity rejection
- missing reverse target rejection
- available-UOM filtering for an existing family

## Known Gaps

- Family code generation still uses a random numeric suffix, so codes are unique by probability rather than by an explicit persisted sequence.
- Reverse-conversion request validation is service-owned because the same request DTO is reused for both forward and reverse flows.

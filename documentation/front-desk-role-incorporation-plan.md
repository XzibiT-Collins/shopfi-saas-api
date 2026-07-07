# Front Desk Role Incorporation Plan

## Purpose

This document describes how to introduce a new `FRONT_DESK` user role into the current backend authorization model without destabilizing the existing `ADMIN` and `CUSTOMER` flows.

The requirement is not only to add a new role, but to make its allowed actions:

- configurable by an admin at a global level
- customizable by an admin for a specific front desk user

The design below is intended to fit the current codebase with minimal disruption.

## Current Structure Summary

The current security model is centered on a single primary role stored directly on `User`.

Observed characteristics in the current codebase:

- `UserRole` currently contains only `ADMIN` and `CUSTOMER`
- `User` stores one enum role via the `roles` field
- JWT and authenticated principal construction currently expose one role authority
- most authorization is implemented using `@PreAuthorize("hasRole('ADMIN')")` or `hasRole('CUSTOMER')`
- some services also hardcode role checks with `UserRole.ADMIN`

This means the system is not yet using a generic permission-based RBAC model.

## Design Goal

Add `FRONT_DESK` in a way that:

- preserves existing `ADMIN` and `CUSTOMER` behavior
- avoids rewriting the whole security model into generic multi-role RBAC
- allows a default set of front desk capabilities to be controlled by admins
- allows per-user exceptions for a specific front desk user

## Recommended Model

Keep the existing primary role model and add a scoped permission layer for `FRONT_DESK`.

### Primary Role

Continue using a single primary role on `User`:

- `ADMIN`
- `CUSTOMER`
- `FRONT_DESK`

This preserves the current authentication and user identity assumptions.

### Front Desk Permissions

Introduce a new permission enum dedicated to front desk users, for example:

- `WALK_IN_ORDER_CREATE`
- `WALK_IN_ORDER_VIEW`
- `WALK_IN_ORDER_MARK_RECEIPT_PRINTED`
- `CUSTOMER_SEARCH`
- `PRODUCT_VIEW_ADMIN_CATALOG`
- `PRODUCT_VIEW_STOCK_SUMMARY`

The exact permission list should map to real workflows, not controllers.

### Global Template Plus User Overrides

Use a two-level permission source for front desk users:

1. Global default front desk permissions
2. Per-user permission overrides

Effective permission resolution:

1. If user is `ADMIN`, keep full access through the current admin logic
2. If user is `CUSTOMER`, keep current customer logic unchanged
3. If user is `FRONT_DESK`, start from the default front desk permission set and then apply any user-specific overrides

This gives admins both:

- a standard front desk baseline
- the ability to tighten or expand one specific front desk user

## Why This Fits the Existing Codebase

This is the least disruptive option because the current codebase is built around one role per user.

Benefits of this approach:

- existing `ADMIN` paths remain stable
- existing `CUSTOMER` paths remain stable
- only features intentionally shared with front desk need to be touched
- permission logic is introduced only where it is needed
- the data model remains understandable

Avoided complexity:

- no immediate full migration to many-to-many user roles
- no need to convert every endpoint into a generic permission matrix
- no need to redesign the entire JWT model around multiple top-level roles

## Proposed Data Model Additions

### New Enum

Add `FRONT_DESK` to `UserRole`.

### New Permission Enum

Add `FrontDeskPermission`.

### New Persistence Structures

Introduce persistence for:

- default front desk permissions
- user-specific front desk permission overrides

Recommended shape:

- one table for default enabled front desk permissions
- one table for per-user permission overrides with an explicit allow or deny

This is preferable to storing serialized JSON on `User`, because it keeps permission logic queryable and easier to validate.

## Authorization Strategy

Do not replace the current authorization model globally.

Instead:

- leave current `ADMIN` and `CUSTOMER` checks in place where front desk should never enter
- only modify modules that front desk should access
- for those modules, allow `FRONT_DESK` through role checks and then enforce fine-grained permission checks

Practical pattern:

- controller allows `ADMIN` and `FRONT_DESK`
- service checks the exact front desk permission when the caller is `FRONT_DESK`
- `ADMIN` bypasses front desk permission restrictions

This keeps sensitive business rules in the service layer and prevents the UI from becoming the only enforcement point.

## Admin Control Requirements

Admin should be able to:

- create a user with the `FRONT_DESK` role
- update a user to the `FRONT_DESK` role
- set the default front desk permission template
- view a front desk user’s effective permissions
- add user-specific overrides for a given front desk user
- remove or reset user-specific overrides

This should sit inside a dedicated admin user-management flow rather than being spread across unrelated modules.

## Initial Functional Scope Recommendation

The first version should be limited to the smallest operationally valuable front desk surface.

Recommended initial scope:

- walk-in order placement
- walk-in order viewing
- customer search for walk-in order association
- receipt printed marking if operationally required

Do not grant broad access to:

- accounting
- tax setup
- category management
- coupon management
- feature flags
- dashboard metrics
- product creation or destructive catalog updates

## Required Cross-Cutting Changes

The following areas need explicit review when introducing `FRONT_DESK`:

- authentication response payloads
- JWT role and authority creation
- `/auth/me` authorization rules
- websocket/admin-session assumptions
- notification recipient logic
- feature flag audience mapping where roles are assumed to be only `ADMIN` and `CUSTOMER`

The risk is not the enum change itself. The risk is hidden assumptions in surrounding systems.

## Risk Assessment

### Low Risk

- adding `FRONT_DESK` to the enum
- exposing the role in auth responses

### Medium Risk

- introducing a permission store for front desk users
- updating controller access on selected modules
- building admin management endpoints for template and override editing

### Higher Risk

- missing service-level `ADMIN` assumptions after controller access is widened
- granting front desk access to endpoints without backend permission checks
- expanding the first release into too many modules at once

## Feasibility

This is feasible without significantly complicating existing logic if implemented as:

- one new primary role
- one scoped permission subsystem
- one admin-managed template
- one per-user override layer
- one limited initial feature surface

It becomes substantially more complex only if the project tries to convert the whole backend into generalized RBAC in the same effort.

## Recommended Decision

Proceed with:

- `FRONT_DESK` as a first-class role
- admin-managed default front desk permissions
- admin-managed per-user front desk overrides
- a narrow first release focused on walk-in and closely related operations

Do not proceed with:

- a full security rewrite
- broad front desk access across all admin modules in the first pass

## Open Decisions Before Build

The implementation will need final product decisions on:

- which exact actions front desk should have in phase one
- whether front desk should receive the same notifications as admins
- whether front desk should be allowed to view stock quantities or only sell available items
- whether front desk can edit existing walk-in orders or only create and view them
- whether front desk should appear in admin-only metrics or staff-management reports

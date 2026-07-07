# Front Desk Role Implementation Plan

## Objective

Implement a `FRONT_DESK` role with admin-managed permissions and per-user overrides, while preserving the current single-role user model and keeping the first release focused on a narrow operational scope.

## Delivery Principles

- preserve existing `ADMIN` and `CUSTOMER` behavior
- avoid a full RBAC rewrite
- enforce permissions on the backend, not only in the frontend
- ship the feature in phases so the blast radius stays controlled

## Phase 1: Core Role and Permission Foundation

### Scope

- add the `FRONT_DESK` role
- add permission enums and persistence model
- add effective permission resolution service
- expose role and effective permissions through authenticated user responses

### Backend Tasks

- add `FRONT_DESK` to `UserRole`
- create `FrontDeskPermission` enum
- create entity and repository for global default front desk permissions
- create entity and repository for per-user front desk permission overrides
- create `FrontDeskAccessService` to resolve effective permissions for a user
- update auth response DTOs so frontend can know the logged-in user’s permissions
- update authentication-related guards that currently assume only `ADMIN` and `CUSTOMER`

### Acceptance Criteria

- a user can exist with role `FRONT_DESK`
- the backend can resolve a front desk user’s effective permissions
- `ADMIN` users remain unaffected
- `CUSTOMER` users remain unaffected

## Phase 2: Admin Management APIs

### Scope

- admin can manage default front desk permissions
- admin can manage a specific front desk user’s overrides
- admin can create and update front desk users

### Backend Tasks

- add admin endpoint to fetch default front desk permission template
- add admin endpoint to update default front desk permission template
- add admin endpoint to fetch a specific front desk user’s override state
- add admin endpoint to update a specific front desk user’s overrides
- add admin endpoint to fetch effective permissions for a front desk user
- add or extend admin user-management endpoints to assign `FRONT_DESK` role

### Validation Rules

- only `ADMIN` can change templates or overrides
- overrides can only be applied to users whose role is `FRONT_DESK`
- invalid permission names must fail validation clearly
- template updates should be idempotent

### Acceptance Criteria

- admin can define a default front desk capability set
- admin can customize one specific front desk user
- effective permissions reflect template plus overrides correctly

## Phase 3: Walk-In Module Enablement

### Scope

Open only the walk-in workflow to front desk users in the first functional rollout.

### Backend Tasks

- update walk-in controller access rules to admit `FRONT_DESK` where appropriate
- replace service assumptions that require strict `ADMIN` role for walk-in execution
- enforce exact front desk permissions in service methods
- ensure `ADMIN` retains unrestricted access to the walk-in module

### Candidate Permission Mapping

- `WALK_IN_ORDER_CREATE` for placing walk-in orders
- `CUSTOMER_SEARCH` for customer lookup during walk-in flow
- `WALK_IN_ORDER_VIEW` for listing and viewing walk-in orders
- `WALK_IN_ORDER_MARK_RECEIPT_PRINTED` for marking receipt print status

### Acceptance Criteria

- admin can use walk-in features exactly as before
- front desk can only use the actions granted by effective permissions
- blocked front desk actions return a proper forbidden response

## Phase 4: Frontend and Admin UX

### Scope

- admin UI for managing front desk template and user overrides
- session-aware frontend gating for front desk users

### Frontend Tasks

- show `FRONT_DESK` as an assignable role in user-management UI
- add admin permission management screen for default front desk template
- add user detail screen or panel for front desk-specific overrides
- update authenticated session store to retain effective permissions
- hide unavailable actions for front desk users based on backend-provided permissions

### UX Constraint

Frontend gating is a usability aid only. Backend permission checks remain mandatory.

### Acceptance Criteria

- admin can configure front desk access without direct database changes
- front desk users only see permitted actions in the UI
- unauthorized attempts are still blocked server-side

## Phase 5: Secondary Systems Review

### Scope

Audit and adjust systems that currently assume admin/customer only.

### Areas To Review

- feature flag audience mapping
- websocket session classification
- notification recipient targeting
- admin-only reports or dashboards
- any role-based filters in repositories or services

### Acceptance Criteria

- no runtime errors occur because `FRONT_DESK` is unhandled in a role switch or mapping
- staff notification behavior is explicitly decided rather than inherited accidentally

## Suggested Technical Design

### Permission Resolution

Recommended effective permission algorithm:

1. load all globally enabled front desk permissions
2. load all overrides for the current front desk user
3. start with the global set
4. apply explicit deny overrides by removing permissions
5. apply explicit allow overrides by adding permissions

### Service-Level Authorization

Recommended pattern:

- if current user role is `ADMIN`, allow
- if current user role is `FRONT_DESK`, check the required `FrontDeskPermission`
- otherwise deny

This is simple, explicit, and compatible with current code patterns.

## Recommended File and Module Touch Points

Expected areas of change:

- `enums`
- `model`
- `repository`
- `service`
- `controller`
- auth DTOs
- JWT/auth utility classes
- tests for access resolution and protected flows

The initial implementation should avoid unrelated refactors in product, accounting, and reporting modules.

## Testing Plan

### Unit Tests

- effective permission calculation
- template-only resolution
- template plus allow override
- template plus deny override
- invalid override target user role
- backend authorization helper behavior

### Integration or Service Tests

- front desk can place walk-in order when permitted
- front desk is blocked from walk-in order when not permitted
- admin remains allowed through the same flow
- admin can update default template
- admin can update a specific front desk user override

### Regression Focus

- auth login and `/auth/me`
- existing admin-only endpoints
- existing customer-only endpoints

## Rollout Recommendation

### First Release

- backend permission foundation
- admin management of template and user overrides
- walk-in workflow only

### Later Expansion

Only after phase one is stable, consider extending front desk access to:

- product lookup helpers
- stock summary read-only views
- limited customer search outside walk-in

Do not expand into accounting, tax configuration, coupon administration, or broader catalog management without a separate review.

## Risks and Mitigations

### Risk

Controller access is widened but service-level authorization is missed.

### Mitigation

Add explicit permission checks in service methods and cover them with tests.

### Risk

Front desk role breaks switch expressions or role mappings that currently support only two roles.

### Mitigation

Audit all role switches and role-to-audience mappings during implementation.

### Risk

The first release scope grows into a broad admin-permission redesign.

### Mitigation

Keep phase one limited to walk-in and staff-management requirements.

## Recommended Build Order

1. Add role enum and permission persistence
2. Build effective permission resolution service
3. Extend auth responses and current-user payload
4. Build admin management endpoints
5. Wire walk-in module to front desk permissions
6. Add frontend admin management and front desk gating
7. Run regression tests and role-path verification

## Definition of Done

The feature is ready for broader use when:

- a front desk user can log in successfully
- admin can manage both defaults and user-specific overrides
- walk-in access is enforced by backend permissions
- existing admin and customer behavior is unchanged
- role-related regression coverage exists for the new flows

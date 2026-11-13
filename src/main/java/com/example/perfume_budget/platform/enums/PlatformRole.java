package com.example.perfume_budget.platform.enums;

/**
 * Role for platform (control-plane) admins. Deliberately kept separate from the
 * per-tenant {@code UserRole} so platform privileges never leak into a tenant schema.
 */
public enum PlatformRole {
    SUPER_ADMIN
}

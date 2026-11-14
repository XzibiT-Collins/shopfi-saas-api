package com.example.perfume_budget.events;

/**
 * @param tenantSlug the tenant the reset was requested on, captured at publish time so the (async) email handler
 *                   builds a link for that tenant regardless of thread-bound context
 */
public record ResetPasswordEvent(
        String email,
        String fullName,
        String token,
        String tenantSlug
) {
}

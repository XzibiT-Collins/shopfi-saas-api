package com.example.perfume_budget.entitlement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * BILL-01c classification of a BACKGROUND entry point (a {@code @Scheduled}, {@code @Async} or event-listener method, or a
 * class that schedules work programmatically): it is NEVER plan-gated and must never reach a service method that calls
 * {@code EntitlementService.requireRoute / requireWrite / requireLimitSlot}. Outside an HTTP request those checks fail
 * closed (a call without a request is a write, plans.md 15), so a background job that reached one would be refused for every
 * restricted tenant. {@code BackgroundCallerScanTest} fails on a background entry point without this annotation (or
 * {@link PlanGatedBackground}), without a reason, or when a {@code NotPlanGated} one reaches a gated check.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NotPlanGated {
    /** Why this background path is not gated (one sentence; shown in the scan's report). */
    String reason();
}

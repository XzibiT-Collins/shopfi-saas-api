package com.example.perfume_budget.entitlement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * BILL-01c classification of a BACKGROUND entry point that starts NEW user-visible gated work for a tenant and therefore
 * deliberately reaches a plan check. The check then applies as a write and fails closed (plans.md 15): a restricted tenant
 * is refused. There is none today; adding one needs this annotation, a reason, and a test of the refusal.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PlanGatedBackground {
    String reason();
}

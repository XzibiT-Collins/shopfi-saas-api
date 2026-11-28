package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PLAN-02 registry marker on a controller handler whose service enforces a plan gate (docs/api/plans.md section 2).
 *
 * <p>This annotation does NOT enforce anything: the check runs inside the service, after authorization (the service's
 * {@code FrontDeskAccessService} check where it has one, otherwise at the start of the method), so every 403 precedes
 * every 402 (plans.md 14.4). It is the registry the build checks: {@code PlanGateRegistryTest} requires one test row per
 * annotated route and per seeded plan, and that every {@link EntitlementService} call site in the code is backed by an
 * annotated route; {@code NeverGatedScanTest} requires that no core route carries it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(RequiresFeature.List.class)
public @interface RequiresFeature {

    PlanFeature value();

    Mode mode() default Mode.ROUTE;

    enum Mode {
        /** The whole route is gated: NOT_IN_PLAN for any method; SUBSCRIPTION_INACTIVE for a method other than GET/HEAD. */
        ROUTE,
        /** Gated only when the request content asks for the feature (THEME_ADVANCED, LOGIN_OTP off -> on, PRODUCT_FAMILIES fields). */
        CONTENT,
        /** A write that may move a row into the counted set of a LIMIT feature (plans.md 15.2). */
        LIMIT
    }

    /** Container of a handler gated by more than one feature (product create: PRODUCT_FAMILIES content + MAX_PRODUCTS). */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        RequiresFeature[] value();
    }
}

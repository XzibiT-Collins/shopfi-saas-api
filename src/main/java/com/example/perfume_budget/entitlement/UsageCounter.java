package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.service.AdvisoryLocks;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Counts the counted sets of docs/api/plans.md 15.2 in the CURRENT tenant's schema (the tenant connection of the request;
 * nothing in the request selects a schema). The same count is the 402 {@code used} and {@code usage[].used}.
 */
@Component
public class UsageCounter {

    static final Map<PlanFeature, String> COUNTS = Map.of(
            // every product row (hard-deleted; variants are rows; inactive / unlisted count)
            PlanFeature.MAX_PRODUCTS, "SELECT count(*) FROM products",
            // active OR exhausted (limit_deactivated); only an admin-switched-off coupon is outside the set
            PlanFeature.MAX_ACTIVE_COUPONS, "SELECT count(*) FROM coupons WHERE is_active = TRUE OR limit_deactivated = TRUE",
            // active FRONT_DESK users only (ADMINs and customers never count)
            PlanFeature.MAX_STAFF, "SELECT count(*) FROM users WHERE roles = 'FRONT_DESK' AND is_active = TRUE",
            // every storage location, active or not (AC6)
            PlanFeature.MAX_LOCATIONS, "SELECT count(*) FROM storage_locations");

    @PersistenceContext
    private EntityManager entityManager;

    public long count(PlanFeature limitFeature) {
        String sql = COUNTS.get(limitFeature);
        if (sql == null) {
            throw new IllegalArgumentException(limitFeature + " is not a LIMIT feature");
        }
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    /**
     * Serializes count-and-insert per tenant and feature (plans.md 15.2): a transaction-scoped advisory lock, released at
     * commit or rollback, so of two concurrent writes at {@code limit - 1} the second counts after the first committed.
     * Two-key form (namespace {@link AdvisoryLocks#PLAN_LIMIT}, hash of tenant id and feature): it cannot collide with another
     * subsystem's lock; tenants never wait for each other (a hash collision inside the namespace only serializes).
     */
    public void lock(long tenantId, PlanFeature limitFeature) {
        AdvisoryLocks.lock(entityManager, AdvisoryLocks.PLAN_LIMIT, tenantId + ":" + limitFeature.name());
    }
}

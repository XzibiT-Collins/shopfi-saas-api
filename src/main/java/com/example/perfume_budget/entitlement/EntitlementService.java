package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.FeatureKind;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.dto.PublicPlanView;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.platform.plan.service.PublicPlanCache;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PLAN-02 plan enforcement (docs/api/plans.md sections 2, 13-15). The tenant comes ONLY from {@link TenantContext}
 * (plans.md 18 item 6): nothing a caller passes selects a tenant. Every check is called from inside the service that
 * does the work, after its authorization (14.4), and answers the 402 bodies of {@link EntitlementErrors}.
 *
 * <ul>
 *   <li>{@link #requireRoute}: a gated route of section 2. NOT_IN_PLAN for any method when the BOOLEAN is off;
 *       SUBSCRIPTION_INACTIVE for a method other than GET/HEAD while {@code readOnly}.</li>
 *   <li>{@link #requireWrite}: a request-content gate that fired (THEME_ADVANCED change, LOGIN_OTP off -> on,
 *       PRODUCT_FAMILIES fields): NOT_IN_PLAN, then SUBSCRIPTION_INACTIVE.</li>
 *   <li>{@link #requireLimitSlot}: a write that moves a row into a counted set (15.2): SUBSCRIPTION_INACTIVE while
 *       {@code readOnly}, then PLAN_LIMIT_REACHED when {@code used >= limit}, counted under a per-tenant-and-feature
 *       transaction lock so count and insert are atomic.</li>
 * </ul>
 * {@code readOnly} is computed on every call from the cached status / trial end against the {@link Clock} bean, never
 * cached. A missing entitlement row reads as off / 0 (never a grant).
 */
@Service
public class EntitlementService {

    private final EntitlementCache cache;
    private final TenantRepository tenants;
    private final PlanService planService;
    private final PublicPlanCache publicPlans;
    private final UsageCounter usage;
    private final DbLockTimeout lockTimeout;
    private final Clock clock;

    public EntitlementService(EntitlementCache cache, TenantRepository tenants, PlanService planService,
                              PublicPlanCache publicPlans, UsageCounter usage, DbLockTimeout lockTimeout, Clock systemClock) {
        this.cache = cache;
        this.tenants = tenants;
        this.planService = planService;
        this.publicPlans = publicPlans;
        this.usage = usage;
        this.lockTimeout = lockTimeout;
        this.clock = systemClock;
    }

    /** The current tenant's effective subscription at this instant. */
    public record Snapshot(TenantPlanState state, Map<PlanFeature, EntitlementView> entitlements, boolean readOnly) {

        public boolean enabled(PlanFeature f) {
            EntitlementView e = entitlements.get(f);
            return e != null && e.enabled() && f.kind() == FeatureKind.BOOLEAN;
        }

        /** LIMIT cap: {@code null} = unlimited; a missing row is 0 (none allowed). */
        public Integer limit(PlanFeature f) {
            EntitlementView e = entitlements.get(f);
            return e == null ? Integer.valueOf(0) : e.limitValue();
        }
    }

    // ------------------------------------------------------------------------------------------ checks

    /** A gated route: reads need the feature; writes (not GET/HEAD) also need an active subscription. */
    public void requireRoute(PlanFeature feature) {
        require(feature, isWriteRequest());
    }

    /** A read whatever the HTTP method (a search with its query in the body): NOT_IN_PLAN only, never read-only. */
    public void requireRead(PlanFeature feature) {
        require(feature, false);
    }

    /** A request-content gate that fired: always a gated write (plans.md 14.1). */
    public void requireWrite(PlanFeature feature) {
        require(feature, true);
    }

    /**
     * A write that moves one row into the counted set of {@code limitFeature}. Must run inside the transaction that then
     * inserts / activates the row: the lock taken here is held until that transaction ends.
     */
    public void requireLimitSlot(PlanFeature limitFeature) {
        checkLimit(limitFeature, true);
    }

    /**
     * The same answer without the lock, for a fast refusal before expensive work outside the transaction (e.g. the
     * product image upload). The locked {@link #requireLimitSlot} must still run inside the writing transaction.
     */
    public void precheckLimitSlot(PlanFeature limitFeature) {
        checkLimit(limitFeature, false);
    }

    public Snapshot current() {
        long tenantId = currentTenantId();
        TenantPlanState state = cache.tenant(tenantId, () -> loadState(tenantId))
                .orElseThrow(() -> new IllegalStateException("tenant row missing for the current request"));
        List<EntitlementView> list = cache.plan(state.planId(),
                () -> planService.entitlements(state.planId()).orElse(List.of()));
        Map<PlanFeature, EntitlementView> byKey = new EnumMap<>(PlanFeature.class);
        for (EntitlementView e : list) {
            PlanFeature.fromKey(e.featureKey()).ifPresent(f -> byKey.put(f, e));
        }
        return new Snapshot(state, byKey, state.readOnlyAt(clock.instant()));
    }

    /** Codes of public active plans that would allow {@code feature} (plans.md 14.3), display order. */
    public List<String> requiredPlans(PlanFeature feature, long used) {
        return publicPlans.get().plans().stream()
                .filter(p -> allows(p, feature, used))
                .map(PublicPlanView::code)
                .toList();
    }

    // ------------------------------------------------------------------------------------------ internals

    private void require(PlanFeature feature, boolean write) {
        if (feature.kind() != FeatureKind.BOOLEAN) {
            throw new IllegalArgumentException(feature + " is a LIMIT feature; use requireLimitSlot");
        }
        Snapshot s = current();
        if (!s.enabled(feature)) {
            throw EntitlementErrors.notInPlan(feature, requiredPlans(feature, 0));
        }
        if (write && s.readOnly()) {
            throw EntitlementErrors.inactive(feature, requiredPlans(feature, 0));
        }
    }

    private void checkLimit(PlanFeature feature, boolean lock) {
        if (feature.kind() != FeatureKind.LIMIT) {
            throw new IllegalArgumentException(feature + " is not a LIMIT feature");
        }
        Snapshot s = current();
        if (s.readOnly()) {
            throw EntitlementErrors.inactive(feature, requiredPlans(feature, usage.count(feature)));
        }
        Integer limit = s.limit(feature);
        if (limit == null) {
            return; // unlimited: never PLAN_LIMIT_REACHED
        }
        if (lock) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("a limit check must run inside the writing transaction");
            }
            long tenantId = s.state().tenantId();
            lockTimeout.run(DbLockTimeout.TRY_AGAIN, DbLockTimeout.TRY_AGAIN_MESSAGE, () -> {
                usage.lock(tenantId, feature);
                return null;
            });
        }
        long used = usage.count(feature);
        if (used >= limit) {
            throw EntitlementErrors.limitReached(feature, limit, used, requiredPlans(feature, used));
        }
    }

    private static boolean allows(PublicPlanView plan, PlanFeature feature, long used) {
        for (EntitlementView e : plan.entitlements()) {
            if (e.featureKey().equals(feature.name())) {
                if (feature.kind() == FeatureKind.BOOLEAN) {
                    return e.enabled();
                }
                return e.limitValue() == null || e.limitValue() > used;
            }
        }
        return false;
    }

    /** Tenant ONLY from TenantContext: its immutable id, or the id of its slug when the identity carries none. */
    long currentTenantId() {
        TenantIdentity identity = TenantContext.get();
        if (identity == null || identity.scope() != TenantScope.TENANT || identity.slug() == null) {
            throw new IllegalStateException("a plan check needs a resolved tenant");
        }
        if (identity.tenantId() != null) {
            return identity.tenantId();
        }
        return tenants.findIdBySlug(identity.slug())
                .orElseThrow(() -> new IllegalStateException("no tenant row for the current tenant"));
    }

    Optional<TenantPlanState> loadState(long tenantId) {
        List<Object[]> rows = tenants.findSubscriptionState(tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        return Optional.of(new TenantPlanState(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                (SubscriptionStatus) r[2], (Instant) r[3]));
    }

    /** GET and HEAD are reads; anything else, and a call outside an HTTP request, is a write (fails closed). */
    static boolean isWriteRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest req = sra.getRequest();
            String m = req.getMethod();
            return !("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m));
        }
        return true;
    }
}

package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.FeatureKind;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.dto.PublicPlanView;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.platform.plan.service.PublicPlanCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@code GET /api/v1/admin/subscription} for the tenant of {@code TenantContext} only (plans.md 13, 18 item 6).
 * Read from the database, not the entitlement cache (the page shows the stored truth); {@code readOnly} computed against
 * the {@link Clock}; usage counted in the tenant's own schema. Never gated by a plan.
 */
@Service
public class SubscriptionService {

    private final EntitlementService entitlements;
    private final PlanService planService;
    private final PublicPlanCache publicPlans;
    private final UsageCounter usage;
    private final Clock clock;

    public SubscriptionService(EntitlementService entitlements, PlanService planService, PublicPlanCache publicPlans,
                               UsageCounter usage, Clock systemClock) {
        this.entitlements = entitlements;
        this.planService = planService;
        this.publicPlans = publicPlans;
        this.usage = usage;
        this.clock = systemClock;
    }

    @Transactional(readOnly = true)
    public SubscriptionView current() {
        long tenantId = entitlements.currentTenantId();
        TenantPlanState state = entitlements.loadState(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant row missing for the current request"));
        PublicPlanView plan = planService.publicPlan(state.planId())
                .orElseThrow(() -> new IllegalStateException("the tenant's plan does not exist"));
        List<SubscriptionView.Usage> used = new ArrayList<>();
        for (PlanFeature f : PlanFeature.values()) {
            if (f.kind() == FeatureKind.LIMIT) {
                used.add(new SubscriptionView.Usage(f.name(), usage.count(f), limitOf(plan.entitlements(), f)));
            }
        }
        return new SubscriptionView(plan, state.status().name(), state.trialEndsAt(), state.readOnlyAt(clock.instant()),
                plan.entitlements(), used, PlanService.FEATURES, publicPlans.get().plans());
    }

    private static Integer limitOf(List<EntitlementView> list, PlanFeature f) {
        for (EntitlementView e : list) {
            if (e.featureKey().equals(f.name())) {
                return e.limitValue(); // null = unlimited
            }
        }
        return 0; // a key without a row reads as 0 (never unlimited)
    }
}

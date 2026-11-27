package com.example.perfume_budget.platform.plan.service;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.FeatureKind;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.PlanPricing;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.dto.FeatureView;
import com.example.perfume_budget.platform.plan.dto.PlanCatalogView;
import com.example.perfume_budget.platform.plan.dto.PlanView;
import com.example.perfume_budget.platform.plan.dto.PublicPlanView;
import com.example.perfume_budget.platform.plan.dto.PublicPlansView;
import com.example.perfume_budget.platform.plan.model.Plan;
import com.example.perfume_budget.platform.plan.model.PlanEntitlement;
import com.example.perfume_budget.platform.plan.repository.PlanEntitlementRepository;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the plan catalog (PLAN-01, docs/api/plans.md). Master data only: it never reads a tenant schema and never
 * takes a tenant from the request. Builds the two response shapes from ONE place each: {@link PlanView} (platform) and
 * {@link PublicPlanView} (public, a separate type). Derived prices always come from {@link PlanPricing}.
 *
 * <p>Seam for PLAN-02: {@link #entitlements(long)} resolves a plan's effective entitlements (catalog order, missing rows
 * read as off / 0, unknown stored keys ignored and logged).
 */
@Slf4j
@Service
public class PlanService {

    public static final List<FeatureView> FEATURES = Arrays.stream(PlanFeature.values()).map(FeatureView::of).toList();

    private final PlanRepository plans;
    private final PlanEntitlementRepository entitlementRepository;
    private final TenantRepository tenants;

    public PlanService(PlanRepository plans, PlanEntitlementRepository entitlementRepository, TenantRepository tenants) {
        this.plans = plans;
        this.entitlementRepository = entitlementRepository;
        this.tenants = tenants;
    }

    /** {@code GET /platform/plans}: every plan (incl. inactive and non-public) in display order, with counts. */
    @Transactional(readOnly = true)
    public PlanCatalogView catalog() {
        List<Plan> all = plans.findAllOrdered();
        Map<Long, List<EntitlementView>> ents = entitlementsByPlan(all.stream().map(Plan::getId).toList());
        Map<Long, Map<String, Long>> counts = tenantCounts();
        return new PlanCatalogView(all.stream()
                .map(p -> platformView(p, ents.get(p.getId()), counts.getOrDefault(p.getId(), emptyCounts())))
                .toList(), FEATURES);
    }

    /** Public active plans, public shape, display order (uncached; {@code PublicPlanCache} fronts it). */
    @Transactional(readOnly = true)
    public PublicPlansView publicPlans() {
        List<Plan> visible = plans.findPublicActiveOrdered();
        Map<Long, List<EntitlementView>> ents = entitlementsByPlan(visible.stream().map(Plan::getId).toList());
        return new PublicPlansView(visible.stream().map(p -> publicView(p, ents.get(p.getId()))).toList(), FEATURES);
    }

    /** The id of the default sign-up plan (owner O1). The plan rules keep exactly one; none is a server fault. */
    @Transactional(readOnly = true)
    public long defaultSignupPlanId() {
        return plans.findDefaultForSignup().map(Plan::getId)
                .orElseThrow(() -> new IllegalStateException("no default sign-up plan is configured"));
    }

    /** One plan's entitlements in catalog order (PLAN-02 seam). Empty when the plan does not exist. */
    @Transactional(readOnly = true)
    public Optional<List<EntitlementView>> entitlements(long planId) {
        if (!plans.existsById(planId)) {
            return Optional.empty();
        }
        return Optional.of(entitlementsByPlan(List.of(planId)).get(planId));
    }

    // ------------------------------------------------------------------------------------------ shared builders

    /**
     * Entitlements of the given plans, one entry per catalog key in catalog order. A key without a stored row reads as
     * off (BOOLEAN) or 0 (LIMIT); a stored key the code no longer knows is ignored and logged, never returned.
     */
    Map<Long, List<EntitlementView>> entitlementsByPlan(Collection<Long> planIds) {
        Map<Long, Map<PlanFeature, PlanEntitlement>> stored = new HashMap<>();
        if (!planIds.isEmpty()) {
            for (PlanEntitlement e : entitlementRepository.findByPlanIds(planIds)) {
                Optional<PlanFeature> f = PlanFeature.fromKey(e.getId().featureKey());
                if (f.isEmpty()) {
                    log.warn("Plan {} has an entitlement for unknown feature key {}; ignored", e.getId().planId(),
                            sanitize(e.getId().featureKey()));
                    continue;
                }
                stored.computeIfAbsent(e.getId().planId(), k -> new EnumMap<>(PlanFeature.class)).put(f.get(), e);
            }
        }
        Map<Long, List<EntitlementView>> out = new HashMap<>();
        for (Long id : planIds) {
            Map<PlanFeature, PlanEntitlement> rows = stored.getOrDefault(id, Map.of());
            out.put(id, Arrays.stream(PlanFeature.values()).map(f -> view(f, rows.get(f))).toList());
        }
        return out;
    }

    /** The response entry for one feature: BOOLEAN keeps limitValue null; LIMIT derives enabled from the limit. */
    static EntitlementView view(PlanFeature f, PlanEntitlement row) {
        if (f.kind() == FeatureKind.BOOLEAN) {
            return new EntitlementView(f.name(), row != null && row.isEnabled(), null);
        }
        Integer limit = row == null ? Integer.valueOf(0) : row.getLimitValue();
        return new EntitlementView(f.name(), limit == null || limit > 0, limit);
    }

    /** Tenants per plan per subscription status; every plan's map has all four keys in contract order. */
    Map<Long, Map<String, Long>> tenantCounts() {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        for (Object[] row : tenants.countByPlanAndSubscriptionStatus()) {
            Long planId = (Long) row[0];
            SubscriptionStatus status = (SubscriptionStatus) row[1];
            out.computeIfAbsent(planId, k -> emptyCounts()).put(status.name(), (Long) row[2]);
        }
        return out;
    }

    static Map<String, Long> emptyCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (SubscriptionStatus s : SubscriptionStatus.values()) {
            m.put(s.name(), 0L);
        }
        return m;
    }

    static PlanView platformView(Plan p, List<EntitlementView> ents, Map<String, Long> counts) {
        PlanPricing.Derived d = PlanPricing.derive(p.getMonthlyPricePesewas(), p.getAnnualDiscountPercent());
        return new PlanView(p.getId(), p.getCode(), p.getName(), p.getTagline(), p.getDescription(),
                p.getMonthlyPricePesewas(), p.getAnnualDiscountPercent(), d.annualPricePesewas(),
                d.annualMonthlyEquivalentPesewas(), d.annualSavingPesewas(), p.getCurrency(), p.getTrialDays(),
                p.getTransactionFeeBps(), p.isPublic(), p.isDefaultForSignup(), p.isHighlighted(), p.getBadgeLabel(),
                p.getBulletsHeading(), List.copyOf(p.getMarketingBullets()), p.getSortOrder(), p.isActive(),
                p.getUpdatedAt(), ents, p.getVersion(), p.getUpdatedBy(), counts);
    }

    static PublicPlanView publicView(Plan p, List<EntitlementView> ents) {
        PlanPricing.Derived d = PlanPricing.derive(p.getMonthlyPricePesewas(), p.getAnnualDiscountPercent());
        return new PublicPlanView(p.getId(), p.getCode(), p.getName(), p.getTagline(), p.getDescription(),
                p.getMonthlyPricePesewas(), p.getAnnualDiscountPercent(), d.annualPricePesewas(),
                d.annualMonthlyEquivalentPesewas(), d.annualSavingPesewas(), p.getCurrency(), p.getTrialDays(),
                p.getTransactionFeeBps(), p.isPublic(), p.isDefaultForSignup(), p.isHighlighted(), p.getBadgeLabel(),
                p.getBulletsHeading(), List.copyOf(p.getMarketingBullets()), p.getSortOrder(), p.isActive(),
                p.getUpdatedAt(), ents);
    }

    /** Stored keys are data: log them without control characters (log forging). */
    private static String sanitize(String key) {
        return key == null ? "null" : key.replaceAll("[^A-Za-z0-9_]", "?");
    }
}

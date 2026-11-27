package com.example.perfume_budget.platform.plan.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.plan.FeatureKind;
import com.example.perfume_budget.platform.plan.PlanAuditAction;
import com.example.perfume_budget.platform.plan.PlanErrors;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.PlanValidationException;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.dto.EntitlementWrite;
import com.example.perfume_budget.platform.plan.dto.PlanAuditPageView;
import com.example.perfume_budget.platform.plan.dto.PlanAuditRowView;
import com.example.perfume_budget.platform.plan.dto.PlanCatalogView;
import com.example.perfume_budget.platform.plan.dto.PlanCreateRequest;
import com.example.perfume_budget.platform.plan.dto.PlanMatrixRequest;
import com.example.perfume_budget.platform.plan.dto.PlanUpdateRequest;
import com.example.perfume_budget.platform.plan.dto.PlanView;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.model.Plan;
import com.example.perfume_budget.platform.plan.model.PlanAuditLog;
import com.example.perfume_budget.platform.plan.model.PlanEntitlement;
import com.example.perfume_budget.platform.plan.model.PlanEntitlementId;
import com.example.perfume_budget.platform.plan.repository.PlanAuditLogRepository;
import com.example.perfume_budget.platform.plan.repository.PlanEntitlementRepository;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Write side of the plan catalog (PLAN-01, docs/api/plans.md sections 6-10). Every write:
 * <ol>
 *   <li>locks EVERY plan row (SELECT ... FOR UPDATE, id order, bounded wait) so version checks and rules see a stable
 *       catalog and two writers from the same version cannot both win;</li>
 *   <li>checks, in this order: unknown id (404), stale version of ANY item (409 PLAN_VERSION_CONFLICT listing every stale
 *       item), request content (400), then the plan rules of section 7 on the FINAL state (400); nothing is written
 *       before all checks passed;</li>
 *   <li>writes the changed plans (version + 1, updatedAt, updatedBy), their entitlements, and ONE audit row per changed
 *       plan in the same transaction (actor id + e-mail, changed fields only);</li>
 *   <li>publishes {@link PlansChangedEvent} inside the transaction (PLAN-02's cache evicts on it after commit) and
 *       registers the after-commit eviction of the public list ({@link PublicPlanCache#evictAfterCommit()}).</li>
 * </ol>
 * A request whose values equal the stored ones is version-checked but writes nothing (no bump, no audit row).
 */
@Service
public class PlanAdminService {

    static final int MAX_AUDIT_PAGE_SIZE = 100;
    static final int DEFAULT_AUDIT_PAGE_SIZE = 20;
    static final int MAX_AUDIT_PAGE = 1_000_000;
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9-]{3,40}$");

    private final PlanRepository plans;
    private final PlanEntitlementRepository entitlementRepository;
    private final PlanAuditLogRepository auditRepository;
    private final PlanService planService;
    private final ApplicationEventPublisher events;
    private final EntityManager em;
    private final Clock clock;
    private final PublicPlanCache publicPlanCache;
    private final ObjectMapper json = new ObjectMapper();

    public PlanAdminService(PlanRepository plans, PlanEntitlementRepository entitlementRepository,
                            PlanAuditLogRepository auditRepository, PlanService planService,
                            ApplicationEventPublisher events, EntityManager em, Clock systemClock,
                            PublicPlanCache publicPlanCache) {
        this.publicPlanCache = publicPlanCache;
        this.plans = plans;
        this.entitlementRepository = entitlementRepository;
        this.auditRepository = auditRepository;
        this.planService = planService;
        this.events = events;
        this.em = em;
        this.clock = systemClock;
    }

    // ------------------------------------------------------------------------------------------ model of one write

    /** One plan's requested change. {@code planId} null = the plan being created. */
    private static final class Change {
        final Long planId;
        final Integer expectedVersion;
        final PlanFields fields;                // null = fields unchanged
        final List<EntitlementWrite> entitlements; // null = entitlements unchanged
        final String fieldPrefix;               // "" (PUT / POST) or "plans[i].plan."
        final String entitlementPrefix;         // "plans[i].entitlements"

        Change(Long planId, Integer expectedVersion, PlanFields fields, List<EntitlementWrite> entitlements,
               String fieldPrefix, String entitlementPrefix) {
            this.planId = planId;
            this.expectedVersion = expectedVersion;
            this.fields = fields;
            this.entitlements = entitlements;
            this.fieldPrefix = fieldPrefix;
            this.entitlementPrefix = entitlementPrefix;
        }
    }

    /** Desired final state of one plan. */
    private static final class Target {
        final Plan plan;                 // the locked entity (or the new, unsaved one)
        final Change change;             // null = only touched by a default-flag move
        PlanFields fields;
        Map<PlanFeature, EntitlementView> entitlements;

        Target(Plan plan, Change change, PlanFields fields, Map<PlanFeature, EntitlementView> entitlements) {
            this.plan = plan;
            this.change = change;
            this.fields = fields;
            this.entitlements = entitlements;
        }

        String prefix() {
            return change == null ? "" : change.fieldPrefix;
        }
    }

    // ------------------------------------------------------------------------------------------ endpoints

    @Transactional
    public PlanView create(PlanCreateRequest req, PlatformPrincipal actor) {
        List<Plan> locked = plans.lockAll();
        if (locked.stream().anyMatch(p -> p.getCode().equals(req.getCode()))) {
            throw PlanErrors.codeTaken();
        }
        Plan fresh = Plan.builder().code(req.getCode()).version(0).build();
        Change change = new Change(null, null, PlanFields.of(req), null, "", "entitlements");
        write(locked, List.of(change), fresh, actor, PlanAuditAction.PLAN_CREATE, PlanAuditAction.PLAN_UPDATE);
        return view(fresh.getId());
    }

    @Transactional
    public PlanView update(long id, PlanUpdateRequest req, PlatformPrincipal actor) {
        List<Plan> locked = plans.lockAll();
        Plan current = locked.stream().filter(p -> p.getId() == id).findFirst().orElseThrow(PlanErrors::notFound);
        checkVersions(Map.of(current, req.getVersion()));
        if (req.getCode() != null && !req.getCode().equals(current.getCode())) {
            throw new PlanValidationException("code", "code cannot be changed");
        }
        Change change = new Change(id, req.getVersion(), PlanFields.of(req), null, "", "entitlements");
        write(locked, List.of(change), null, actor, PlanAuditAction.PLAN_UPDATE, PlanAuditAction.PLAN_UPDATE);
        return view(id);
    }

    @Transactional
    public PlanCatalogView matrix(PlanMatrixRequest req, PlatformPrincipal actor) {
        // plans.md section 8 order: 400 field validation (bean validation, then the request content below: duplicate ids,
        // items without a change, unknown / duplicate feature keys, wrong kind, absent LIMIT value) -> 404 unknown plan id
        // -> 409 stale version of ANY item -> 400 plan rules on the final state
        Map<String, String> shape = new LinkedHashMap<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < req.plans().size(); i++) {
            PlanMatrixRequest.Item item = req.plans().get(i);
            if (!ids.add(item.id())) {
                shape.put("plans[" + i + "].id", "duplicate plan id");
            }
            if (item.entitlements() == null && item.plan() == null) {
                shape.put("plans[" + i + "]", "must contain entitlements or plan");
            }
            if (item.entitlements() != null) {
                // request-content checks need no stored state, so they are field validation: before 404 and 409
                validateEntitlements(item.entitlements(), "plans[" + i + "].entitlements", shape);
            }
        }
        if (!shape.isEmpty()) {
            throw new PlanValidationException(shape);
        }
        List<Plan> locked = plans.lockAll();
        Map<Long, Plan> byId = new HashMap<>();
        locked.forEach(p -> byId.put(p.getId(), p));
        Map<Plan, Integer> versions = new LinkedHashMap<>();
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < req.plans().size(); i++) {
            PlanMatrixRequest.Item item = req.plans().get(i);
            Plan p = byId.get(item.id().longValue());
            if (p == null) {
                throw PlanErrors.notFound();
            }
            versions.put(p, item.version());
            changes.add(new Change(p.getId(), item.version(), item.plan() == null ? null : PlanFields.of(item.plan()),
                    item.entitlements(), "plans[" + i + "].plan.", "plans[" + i + "].entitlements"));
        }
        checkVersions(versions);
        write(locked, changes, null, actor, PlanAuditAction.PLAN_MATRIX_UPDATE, PlanAuditAction.PLAN_MATRIX_UPDATE);
        em.flush();
        return planService.catalog();
    }

    @Transactional(readOnly = true)
    public PlanAuditPageView audit(Integer page, Integer size, Long planId, String tenantSlug) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (p > MAX_AUDIT_PAGE) {
            throw new BadRequestException("page must be <= " + MAX_AUDIT_PAGE);
        }
        if (size != null && size < 1) {
            throw new BadRequestException("size must be >= 1");
        }
        if (planId != null && planId < 1) {
            throw new BadRequestException("planId must be >= 1");
        }
        if (tenantSlug != null && !SLUG_SHAPE.matcher(tenantSlug).matches()) {
            throw new BadRequestException("tenantSlug is invalid");
        }
        int s = size == null ? DEFAULT_AUDIT_PAGE_SIZE : Math.min(size, MAX_AUDIT_PAGE_SIZE);
        Page<PlanAuditLog> result = auditRepository.search(planId, tenantSlug, PageRequest.of(p, s));
        return new PlanAuditPageView(result.getContent().stream().map(PlanAuditRowView::from).toList(), p, s,
                result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------------------------------ the write

    /** 409 listing EVERY stale plan (expected = what the client sent, current = what is stored). */
    private static void checkVersions(Map<Plan, Integer> expected) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        expected.forEach((plan, version) -> {
            if (plan.getVersion() != version.longValue()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("planId", plan.getId());
                c.put("expectedVersion", version);
                c.put("currentVersion", plan.getVersion());
                conflicts.add(c);
            }
        });
        if (!conflicts.isEmpty()) {
            throw PlanErrors.versionConflict(conflicts);
        }
    }

    /**
     * Validates the final state and writes it. {@code fresh} is the plan being created (else null). Returns the ids of
     * the plans that actually changed.
     */
    private Set<Long> write(List<Plan> locked, List<Change> changes, Plan fresh, PlatformPrincipal actor,
                            PlanAuditAction action, PlanAuditAction movedAction) {
        Map<Long, Map<PlanFeature, EntitlementView>> stored = new HashMap<>();
        planService.entitlementsByPlan(locked.stream().map(Plan::getId).toList()).forEach((id, list) -> {
            Map<PlanFeature, EntitlementView> m = new EnumMap<>(PlanFeature.class);
            list.forEach(e -> m.put(PlanFeature.valueOf(e.featureKey()), e));
            stored.put(id, m);
        });

        // final state of every plan (unchanged ones included: the rules look at the whole catalog)
        Map<Long, Target> targets = new LinkedHashMap<>();
        for (Plan p : locked) {
            targets.put(p.getId(), new Target(p, null, PlanFields.of(p), new EnumMap<>(stored.get(p.getId()))));
        }
        Target freshTarget = null;
        Map<String, String> errors = new LinkedHashMap<>();
        List<Target> requested = new ArrayList<>();
        for (Change c : changes) {
            Target t;
            if (c.planId == null) {
                Map<PlanFeature, EntitlementView> defaults = new EnumMap<>(PlanFeature.class);
                for (PlanFeature f : PlanFeature.values()) {
                    defaults.put(f, PlanService.view(f, null));
                }
                t = new Target(fresh, c, c.fields, defaults);
                freshTarget = t;
            } else {
                Target old = targets.get(c.planId);
                t = new Target(old.plan, c, old.fields, old.entitlements);
                targets.put(c.planId, t);
            }
            if (c.fields != null) {
                t.fields = c.fields;
            }
            if (c.entitlements != null) {
                applyEntitlements(t, c, errors);
            }
            requested.add(t);
        }
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
        List<Target> all = new ArrayList<>(targets.values());
        if (freshTarget != null) {
            all.add(freshTarget);
        }
        Target moved = moveDefault(all, requested, errors);
        checkRules(all, requested, moved, errors);
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }

        // ---- every check passed: write. The plan losing the default flag is flushed FIRST (one-default unique index).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Set<Long> changedIds = new LinkedHashSet<>();
        List<Target> clearing = new ArrayList<>(); // plans that lose the default flag: written and flushed first
        if (moved != null) {
            clearing.add(moved);
        }
        for (Target t : requested) {
            if (t.plan.getId() != null && t.plan.isDefaultForSignup() && !t.fields.isDefaultForSignup()) {
                clearing.add(t);
            }
        }
        for (Target t : clearing) {
            persist(t, stored.get(t.plan.getId()), now, actor, t == moved ? movedAction : action, changedIds);
        }
        em.flush();
        for (Target t : requested) {
            if (clearing.contains(t)) {
                continue;
            }
            Map<PlanFeature, EntitlementView> before = t.plan.getId() == null ? null : stored.get(t.plan.getId());
            persist(t, before, now, actor, t == freshTarget ? PlanAuditAction.PLAN_CREATE : action, changedIds);
        }
        em.flush();
        if (!changedIds.isEmpty()) {
            events.publishEvent(new PlansChangedEvent(Set.copyOf(changedIds))); // PLAN-02 entitlement cache seam
            publicPlanCache.evictAfterCommit();
        }
        return changedIds;
    }

    /** The content rules of plans.md section 3.3 for one item's entitlement writes (no stored state needed). */
    private static void validateEntitlements(List<EntitlementWrite> writes, String prefix, Map<String, String> errors) {
        Set<PlanFeature> seen = new HashSet<>();
        for (int j = 0; j < writes.size(); j++) {
            EntitlementWrite w = writes.get(j);
            String at = prefix + "[" + j + "].";
            PlanFeature f = PlanFeature.fromKey(w.getFeatureKey()).orElse(null);
            if (f == null) {
                errors.put(at + "featureKey", "unknown feature");
            } else if (!seen.add(f)) {
                errors.put(at + "featureKey", "duplicate feature");
            } else if (f.kind() == FeatureKind.BOOLEAN && w.getLimitValue() != null) {
                errors.put(at + "limitValue", "must be null for a BOOLEAN feature");
            } else if (f.kind() == FeatureKind.BOOLEAN && w.getEnabled() == null) {
                errors.put(at + "enabled", "must not be null");
            } else if (f.kind() == FeatureKind.LIMIT && !w.isLimitValueSent()) {
                // never read an absent limit as "unlimited"
                errors.put(at + "limitValue", "is required for a LIMIT feature (null = unlimited)");
            }
        }
    }

    /** Applies a matrix item's (already validated) entitlement changes to the target (plans.md section 3.3). */
    private static void applyEntitlements(Target t, Change c, Map<String, String> errors) {
        Map<PlanFeature, EntitlementView> next = new EnumMap<>(t.entitlements);
        Set<PlanFeature> seen = new HashSet<>();
        for (int j = 0; j < c.entitlements.size(); j++) {
            EntitlementWrite w = c.entitlements.get(j);
            String at = c.entitlementPrefix + "[" + j + "].";
            PlanFeature f = PlanFeature.fromKey(w.getFeatureKey()).orElse(null);
            if (f == null) {
                errors.put(at + "featureKey", "unknown feature");
                continue;
            }
            if (!seen.add(f)) {
                errors.put(at + "featureKey", "duplicate feature");
                continue;
            }
            if (f.kind() == FeatureKind.BOOLEAN) {
                if (w.getLimitValue() != null) {
                    errors.put(at + "limitValue", "must be null for a BOOLEAN feature");
                } else if (w.getEnabled() == null) {
                    errors.put(at + "enabled", "must not be null");
                } else {
                    next.put(f, new EntitlementView(f.name(), w.getEnabled(), null));
                }
            } else if (!w.isLimitValueSent()) {
                // never read an absent limit as "unlimited"
                errors.put(at + "limitValue", "is required for a LIMIT feature (null = unlimited)");
            } else {
                Integer limit = w.getLimitValue();
                next.put(f, new EntitlementView(f.name(), limit == null || limit > 0, limit));
            }
        }
        t.entitlements = next;
    }

    /**
     * Section 7 rule 1: setting {@code isDefaultForSignup: true} on a plan MOVES the flag: the current default loses it in
     * the same transaction (own version bump and audit row). Returns that plan's target, or null when nothing moved.
     */
    private static Target moveDefault(List<Target> all, List<Target> requested, Map<String, String> errors) {
        List<Target> claiming = new ArrayList<>();
        for (Target t : requested) {
            if (t.change.fields != null && t.fields.isDefaultForSignup()) {
                claiming.add(t);
            }
        }
        if (claiming.size() > 1) {
            Target second = claiming.get(1);
            errors.put(second.prefix() + "isDefaultForSignup", "only one plan can be the default");
            return null;
        }
        if (claiming.isEmpty()) {
            return null;
        }
        Target winner = claiming.get(0);
        for (Target t : all) {
            if (t != winner && t.plan.isDefaultForSignup() && t.fields.isDefaultForSignup()) {
                t.fields = t.fields.withDefaultForSignup(false);
                return t;
            }
        }
        return null;
    }

    /** Section 7 rules on the final state; the field is named on the plan whose change caused the violation. */
    private static void checkRules(List<Target> all, List<Target> requested, Target moved, Map<String, String> errors) {
        if (!errors.isEmpty()) {
            return;
        }
        List<Target> defaults = all.stream().filter(t -> t.fields.isDefaultForSignup()).toList();
        if (defaults.isEmpty()) {
            Target culprit = requested.stream().filter(t -> t.plan.isDefaultForSignup()).findFirst().orElse(requested.get(0));
            errors.put(culprit.prefix() + "isDefaultForSignup", "another plan must become the default first");
            return;
        }
        Target def = defaults.get(0);
        boolean flagSetNow = def.change != null && def.change.fields != null && !def.plan.isDefaultForSignup();
        if (!def.fields.active()) {
            if (flagSetNow) {
                errors.put(def.prefix() + "isDefaultForSignup", "an inactive plan cannot be the default");
            } else {
                errors.put(def.prefix() + "active", "the default sign-up plan cannot be deactivated");
            }
        }
        if (def.fields.trialDays() < 1) {
            errors.put(def.prefix() + "trialDays", "the default sign-up plan needs a trial of at least 1 day");
        }
        boolean publicActiveRemains = all.stream().anyMatch(t -> t.fields.isPublic() && t.fields.active());
        if (!publicActiveRemains) {
            Target culprit = requested.stream()
                    .filter(t -> t.change.fields != null && t.plan.getId() != null && t.plan.isPublic() && t.plan.isActive())
                    .findFirst().orElse(requested.get(0));
            String field = culprit.plan.getId() != null && culprit.plan.isPublic() && !culprit.fields.isPublic()
                    ? "isPublic" : "active";
            errors.put(culprit.prefix() + field, "at least one public active plan must remain");
        }
    }

    /** Writes one plan (when anything differs) with its entitlements and its audit row. */
    private void persist(Target t, Map<PlanFeature, EntitlementView> beforeEnts, Instant now, PlatformPrincipal actor,
                         PlanAuditAction action, Set<Long> changedIds) {
        boolean creating = t.plan.getId() == null;
        Map<String, Object> beforeFields = creating ? null : PlanFields.of(t.plan).asJson();
        Map<String, Object> afterFields = t.fields.asJson();
        ArrayNode diff = json.createArrayNode();
        if (creating) {
            diff.add(change("code", null, t.plan.getCode()));
        }
        afterFields.forEach((k, v) -> {
            Object b = beforeFields == null ? null : beforeFields.get(k);
            if (creating || !Objects.equals(b, v)) {
                diff.add(change(k, b, v));
            }
        });
        List<PlanFeature> entChanged = new ArrayList<>();
        if (!creating) {
            for (PlanFeature f : PlanFeature.values()) {
                EntitlementView b = beforeEnts.get(f);
                EntitlementView a = t.entitlements.get(f);
                Object bv = f.kind() == FeatureKind.BOOLEAN ? (Object) b.enabled() : b.limitValue();
                Object av = f.kind() == FeatureKind.BOOLEAN ? (Object) a.enabled() : a.limitValue();
                if (!Objects.equals(bv, av)) {
                    entChanged.add(f);
                    diff.add(change("entitlements." + f.name(), bv, av));
                }
            }
        }
        if (diff.isEmpty()) {
            return; // equal to the stored values: version-checked, nothing written
        }
        t.fields.applyTo(t.plan);
        t.plan.setVersion(creating ? 0 : t.plan.getVersion() + 1);
        t.plan.setUpdatedAt(now);
        t.plan.setUpdatedBy(actor.email());
        if (creating) {
            plans.saveAndFlush(t.plan);
            for (PlanFeature f : PlanFeature.values()) {
                EntitlementView e = t.entitlements.get(f);
                entitlementRepository.save(new PlanEntitlement(new PlanEntitlementId(t.plan.getId(), f.name()),
                        e.enabled(), e.limitValue()));
            }
        } else {
            for (PlanFeature f : entChanged) {
                EntitlementView e = t.entitlements.get(f);
                PlanEntitlementId id = new PlanEntitlementId(t.plan.getId(), f.name());
                PlanEntitlement row = entitlementRepository.findById(id).orElseGet(() -> new PlanEntitlement(id, false, null));
                row.setEnabled(e.enabled());
                row.setLimitValue(e.limitValue());
                entitlementRepository.save(row);
            }
        }
        auditRepository.saveAndFlush(PlanAuditLog.builder()
                .actorAdminId(actor.id()).actorEmail(actor.email()).action(action)
                .planId(t.plan.getId()).planCode(t.plan.getCode()).changes(diff).build());
        changedIds.add(t.plan.getId());
    }

    private ObjectNode change(String field, Object before, Object after) {
        ObjectNode n = json.createObjectNode();
        n.put("field", field);
        n.set("before", json.valueToTree(before));
        n.set("after", json.valueToTree(after));
        return n;
    }

    /** The saved plan in the platform shape (tenant counts included). */
    private PlanView view(long id) {
        em.flush();
        Plan p = plans.findById(id).orElseThrow(PlanErrors::notFound);
        Map<Long, Map<String, Long>> counts = planService.tenantCounts();
        return PlanService.platformView(p, planService.entitlementsByPlan(List.of(id)).get(id),
                counts.getOrDefault(id, PlanService.emptyCounts()));
    }
}

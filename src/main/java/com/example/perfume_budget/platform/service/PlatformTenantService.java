package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.dto.PlatformAuditPage;
import com.example.perfume_budget.platform.dto.PlatformAuditRow;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.PlatformTenantSummary;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantAuditLog;
import com.example.perfume_budget.platform.model.TenantSignup;
import com.example.perfume_budget.platform.repository.TenantAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.platform.service.TenantStateMachine.Decision;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.tenancy.InvalidProvisioningRequestException;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.TenantAlreadyExistsException;
import com.example.perfume_budget.tenancy.TenantProvisioningException;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.PlanAuditAction;
import com.example.perfume_budget.platform.plan.PlanErrors;
import com.example.perfume_budget.platform.plan.PlanValidationException;
import com.example.perfume_budget.platform.plan.dto.TenantPlanUpdateRequest;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.example.perfume_budget.platform.plan.model.Plan;
import com.example.perfume_budget.platform.plan.model.PlanAuditLog;
import com.example.perfume_budget.platform.plan.repository.PlanAuditLogRepository;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.criteria.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Platform tenant management (PLAT-02). Runs in PLATFORM scope and touches only the master schema, plus the PROV-01
 * saga (which binds and restores its own tenant context) for approve/reprovision, and (EVT-01c) the tenant's Flyway
 * migrations before a reactivation. Never reads tenant business data.
 *
 * <p>Concurrency model: every status write is a compare-and-set ({@code TenantRepository.transitionStatus}) so two
 * racing callers cannot both win; the loser re-reads and reports an idempotent no-op. The audit row of a
 * suspend/reactivate is written in the SAME master transaction as the status change (all or nothing). For
 * approve/reprovision the saga commits in separate steps, and the audit table is append-only, so TWO rows are written
 * by the caller that won the saga's claim: an INTENT row (previous status to PROVISIONING) committed atomically with
 * the claim and before any DDL, and an OUTCOME row (previous status to ACTIVE/FAILED) after the saga. The signup
 * password hash is deleted in its own transaction, independent of the audit insert. A crash between the saga and the
 * OUTCOME write therefore leaves the INTENT row; a retry on the now-ACTIVE tenant reconciles (hash + RECONCILE row).
 *
 * <p>Caches: after every action that may change tenant visibility the CORS decision cache is dropped with
 * {@link TenantOriginPolicy#invalidateAll()} AFTER the DB commit. Tenant resolution itself reads the registry on every
 * request (no cache), so a suspended tenant answers 423 on its very next request.
 */
@Slf4j
@Service
public class PlatformTenantService {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9-]{3,40}$");
    /** API sort keys to entity properties (allow-list: nothing user-supplied reaches the query as a property name). */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt", "slug", "slug", "displayName", "displayName", "status", "status");

    private final TenantRepository tenantRepository;
    private final TenantSignupRepository signupRepository;
    private final TenantAuditLogRepository auditRepository;
    private final TenantProvisioningService provisioningService;
    private final TenantOriginPolicy originPolicy;
    private final TenantSchemaMigrator schemaMigrator;
    private final TransactionTemplate masterTx;
    private final TokenBucketRateLimiter actionLimiter;
    private final Duration stuckAfter;
    /** An INTENT younger than this is presumed still in flight, so a retry does not write a RECONCILE row for it. */
    private final Duration reconcileGrace;
    private final PlanRepository planRepository;
    private final PlanAuditLogRepository planAuditRepository;
    private final ApplicationEventPublisher events;

    public PlatformTenantService(TenantRepository tenantRepository, TenantSignupRepository signupRepository,
                                 TenantAuditLogRepository auditRepository,
                                 TenantProvisioningService provisioningService, TenantOriginPolicy originPolicy,
                                 TenantSchemaMigrator schemaMigrator, PlatformTransactionManager txManager,
                                 @Value("${platform.tenants.actions-per-minute:30}") int actionsPerMinute,
                                 @Value("${platform.tenants.stuck-provisioning-minutes:15}") long stuckMinutes,
                                 @Value("${platform.tenants.reconcile-grace-seconds:60}") long reconcileGraceSeconds,
                                 PlanRepository planRepository, PlanAuditLogRepository planAuditRepository,
                                 ApplicationEventPublisher events) {
        this.planRepository = planRepository;
        this.planAuditRepository = planAuditRepository;
        this.events = events;
        this.tenantRepository = tenantRepository;
        this.signupRepository = signupRepository;
        this.auditRepository = auditRepository;
        this.provisioningService = provisioningService;
        this.originPolicy = originPolicy;
        this.schemaMigrator = schemaMigrator;
        this.masterTx = new TransactionTemplate(txManager);
        this.actionLimiter = new TokenBucketRateLimiter(Math.max(1, actionsPerMinute), Duration.ofMinutes(1));
        this.stuckAfter = Duration.ofMinutes(Math.max(1, stuckMinutes));
        this.reconcileGrace = Duration.ofSeconds(Math.max(0, reconcileGraceSeconds));
    }

    // ------------------------------------------------------------------------------------------ list

    public static final int MAX_SEARCH_LENGTH = 100;
    /** Upper bound for {@code page} (the offset page*size must never overflow an int or reach the database as nonsense). */
    public static final int MAX_PAGE = 1_000_000;
    /** Audit sort keys to entity properties (allow-list). */
    static final Map<String, String> AUDIT_SORT_FIELDS = Map.of("id", "id", "occurredAt", "occurredAt");

    private record Paging(int page, int size, String key, String property, Sort.Direction dir) {
        PageRequest request() {
            // The unique id is always the final tiebreaker so equal sort keys never reorder between pages.
            return PageRequest.of(page, size, Sort.by(dir, property).and(Sort.by(dir, "id")));
        }

        String dirName() {
            return dir.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static Paging paging(Integer page, Integer size, String sortKey, String direction, String defaultKey,
                                 Map<String, String> allowed, Sort.Direction defaultDir) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (p > MAX_PAGE) {
            throw new BadRequestException("page must be <= " + MAX_PAGE);
        }
        if (size != null && size < 1) {
            throw new BadRequestException("size must be >= 1");
        }
        int effectiveSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String key = sortKey == null || sortKey.isBlank() ? defaultKey : sortKey.strip();
        String property = allowed.get(key);
        if (property == null) {
            throw new BadRequestException("Unsupported sort field");
        }
        Sort.Direction dir;
        if (direction == null || direction.isBlank()) {
            dir = defaultDir;
        } else if (direction.equalsIgnoreCase("asc")) {
            dir = Sort.Direction.ASC;
        } else if (direction.equalsIgnoreCase("desc")) {
            dir = Sort.Direction.DESC;
        } else {
            throw new BadRequestException("direction must be asc or desc");
        }
        return new Paging(p, effectiveSize, key, property, dir);
    }

    @Transactional(readOnly = true)
    public PlatformTenantPage list(String statusFilter, Integer page, Integer size, String sortKey, String direction) {
        return list(statusFilter, page, size, sortKey, direction, null);
    }

    @Transactional(readOnly = true)
    public PlatformTenantPage list(String statusFilter, Integer page, Integer size, String sortKey, String direction,
                                   String search) {
        return list(statusFilter, page, size, sortKey, direction, search, null, null);
    }

    /**
     * API-RD-07 AC2: adds {@code customDomainStatus} (PENDING / VERIFIED / FAILED, case-insensitive; anything else is a
     * 400) and {@code hasCustomDomain} (true: a domain is set; false: none). All filters AND together, so
     * {@code customDomainStatus=VERIFIED&hasCustomDomain=false} is an empty page, not an error. The response always
     * carries {@link PlatformTenantPage#customDomainCounts()}.
     */
    @Transactional(readOnly = true)
    public PlatformTenantPage list(String statusFilter, Integer page, Integer size, String sortKey, String direction,
                                   String search, String customDomainStatusFilter, Boolean hasCustomDomain) {
        TenantStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                status = TenantStatus.valueOf(statusFilter.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown status filter");
            }
        }
        CustomDomainStatus domainStatus = null;
        if (customDomainStatusFilter != null && !customDomainStatusFilter.isBlank()) {
            try {
                domainStatus = CustomDomainStatus.valueOf(customDomainStatusFilter.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown customDomainStatus filter");
            }
        }
        Paging pg = paging(page, size, sortKey, direction, "createdAt", SORT_FIELDS, Sort.Direction.DESC);
        String term = normalizeSearch(search);
        // Without a search term or a custom-domain filter the pre-PLAT-04 queries are used unchanged.
        Page<Tenant> result;
        if (term != null || domainStatus != null || hasCustomDomain != null) {
            result = tenantRepository.findAll(filter(status, term, domainStatus, hasCustomDomain), pg.request());
        } else if (status == null) {
            result = tenantRepository.findAll(pg.request());
        } else {
            result = tenantRepository.findByStatus(status, pg.request());
        }
        List<PlatformTenantSummary> items = summaries(result.getContent());
        return new PlatformTenantPage(items, pg.page(), pg.size(), result.getTotalElements(), result.getTotalPages(),
                pg.key(), pg.dirName(), customDomainCounts(status, term));
    }

    /** Facet counts for the page header: the status/search filters apply, the custom-domain filters do not. */
    private Map<String, Long> customDomainCounts(TenantStatus status, String term) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (CustomDomainStatus s : CustomDomainStatus.values()) {
            counts.put(s.name(), tenantRepository.count(filter(status, term, s, null)));
        }
        counts.put("NONE", tenantRepository.count(filter(status, term, null, false)));
        return counts;
    }

    /** Blank means no search. Control characters are a 400. Longer than {@link #MAX_SEARCH_LENGTH} is a 400 (never silently truncated). */
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        if (search.chars().anyMatch(Character::isISOControl)) {
            // checked on the raw input, before strip(): e.g. a NUL byte would otherwise surface as a database error
            throw new BadRequestException("search contains invalid characters");
        }
        if (search.isBlank()) {
            return null;
        }
        String t = search.strip();
        if (t.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException("search must be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        return t;
    }

    /**
     * Escapes LIKE wildcards so user input only ever matches literally. The term is NOT case-folded here: it is bound as
     * is and lowered by the database ({@code lower(:pattern)}), the same function that lowers the column, so Java and
     * Postgres can never disagree on folding (e.g. U+0130).
     */
    static String likePattern(String term) {
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Status filter plus case-insensitive contains-search on slug, display name and owner email; all parameterised. */
    private static Specification<Tenant> filter(TenantStatus status, String term, CustomDomainStatus domainStatus,
                                                Boolean hasCustomDomain) {
        return (root, query, cb) -> {
            List<Predicate> all = new java.util.ArrayList<>();
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (term != null) {
                var pattern = cb.lower(cb.literal(likePattern(term)));
                all.add(cb.or(
                        cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                        cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("ownerEmail")), pattern, '\\')));
            }
            if (domainStatus != null) {
                all.add(cb.equal(root.get("customDomainStatus"), domainStatus));
            }
            if (hasCustomDomain != null) {
                all.add(hasCustomDomain ? cb.isNotNull(root.get("customDomain")) : cb.isNull(root.get("customDomain")));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    /** Tenant detail (PLAT-04): registry fields only, same shape as a list row. */
    @Transactional(readOnly = true)
    public PlatformTenantSummary detail(String slug) {
        return summary(require(slug));
    }

    /** Audit trail of one existing tenant (PLAT-04). Newest first by default; writes nothing. */
    @Transactional(readOnly = true)
    public PlatformAuditPage audit(String slug, Integer page, Integer size, String sortKey, String direction) {
        Paging pg = paging(page, size, sortKey, direction, "id", AUDIT_SORT_FIELDS, Sort.Direction.DESC);
        Tenant tenant = require(slug);
        Page<TenantAuditLog> result = auditRepository.findByTenantId(tenant.getId(), pg.request());
        List<PlatformAuditRow> items = result.getContent().stream().map(PlatformAuditRow::from).toList();
        return new PlatformAuditPage(items, pg.page(), pg.size(), result.getTotalElements(), result.getTotalPages(),
                pg.key(), pg.dirName());
    }

    // ------------------------------------------------------------------------------------------ suspend / reactivate

    public TenantActionResponse suspend(String slug, PlatformPrincipal actor) {
        return flip(slug, actor, TenantAction.SUSPEND);
    }

    /**
     * EVT-01c (6): boot migrates only ACTIVE tenant schemas, so a tenant that was SUSPENDED across a deploy missed that
     * deploy's tenant migrations (a missing V13 {@code side_effect_outbox} broke every checkout). Its schema is brought
     * up to date BEFORE the status flips back to ACTIVE, so no request ever reaches an unmigrated schema; if the
     * migration fails the tenant stays SUSPENDED (500, nothing audited) and the call can be repeated. Migrating is
     * idempotent, so a reactivation that then loses the status race changes nothing.
     */
    public TenantActionResponse reactivate(String slug, PlatformPrincipal actor) {
        return flip(slug, actor, TenantAction.REACTIVATE);
    }

    private TenantActionResponse flip(String slug, PlatformPrincipal actor, TenantAction action) {
        throttle(actor);
        Tenant initial = require(slug);
        if (action == TenantAction.REACTIVATE
                && TenantStateMachine.decide(action, initial.getStatus()) == Decision.TRANSITION) {
            migrateBeforeReactivation(initial);
        }
        try {
            TenantActionResponse r = masterTx.execute(s -> {
                // Re-read inside the transaction; the compare-and-set below is the real guard.
                Tenant t = tenantRepository.findById(initial.getId()).orElseThrow(() -> notFound());
                TenantStatus prev = t.getStatus();
                Decision d = TenantStateMachine.decide(action, prev);
                if (d == Decision.ILLEGAL) {
                    throw new IllegalTenantTransitionException(action, prev, "");
                }
                if (d == Decision.NO_OP) {
                    return response(action, false, false, prev, t);
                }
                TenantStatus to = TenantStateMachine.target(action);
                int moved = tenantRepository.transitionStatus(t.getId(), List.of(prev), to);
                if (moved != 1) {
                    // Lost a race between our read and write: report the state that now holds, change nothing.
                    Tenant now = tenantRepository.findById(t.getId()).orElseThrow(() -> notFound());
                    Decision again = TenantStateMachine.decide(action, now.getStatus());
                    if (again == Decision.ILLEGAL) {
                        throw new IllegalTenantTransitionException(action, now.getStatus(), "");
                    }
                    return response(action, false, false, now.getStatus(), now);
                }
                audit(actor, t, action, prev, to);
                Tenant after = tenantRepository.findById(t.getId()).orElseThrow(() -> notFound());
                return response(action, true, false, prev, after);
            });
            log.info("Platform admin {} {} tenant {}", actor.id(), action, slug);
            return r;
        } finally {
            originPolicy.invalidateAll(); // after commit (or after a rollback: harmless), never before
        }
    }

    private void migrateBeforeReactivation(Tenant tenant) {
        try {
            schemaMigrator.migrateTenant(tenant.getSchemaName());
        } catch (RuntimeException e) {
            // class only: a Flyway / JDBC message can carry the JDBC URL or SQL text
            log.error("Tenant {} not reactivated: its schema migration failed ({})", tenant.getSlug(), e.getClass().getName());
            throw new TenantMigrationFailedException(tenant.getSlug());
        }
    }

    // ------------------------------------------------------------------------------------------ approve / reprovision

    public TenantActionResponse approve(String slug, PlatformPrincipal actor) {
        return provisionAction(slug, actor, TenantAction.APPROVE);
    }

    public TenantActionResponse reprovision(String slug, PlatformPrincipal actor) {
        return provisionAction(slug, actor, TenantAction.REPROVISION);
    }

    private TenantActionResponse provisionAction(String slug, PlatformPrincipal actor, TenantAction action) {
        throttle(actor);
        Tenant tenant = require(slug);
        try {
            Decision d = TenantStateMachine.decide(action, tenant.getStatus());
            if (d == Decision.ILLEGAL) {
                throw new IllegalTenantTransitionException(action, tenant.getStatus(), "");
            }
            if (d == Decision.NO_OP && !(action == TenantAction.REPROVISION && isStuck(tenant))) {
                if (tenant.getStatus() == TenantStatus.ACTIVE) {
                    reconcile(actor, tenant, action);
                }
                return response(action, false, tenant.getStatus() == TenantStatus.PROVISIONING,
                        tenant.getStatus(), tenant);
            }
            if (d == Decision.NO_OP) {
                // Stuck PROVISIONING (saga died): compare-and-set it to FAILED; only one caller wins and proceeds.
                // The RECLAIM audit row is written in the same transaction as the move (all or nothing).
                final Tenant stuck = tenant;
                int reclaimed = masterTx.execute(s -> {
                    int n = tenantRepository.reclaimStuckProvisioning(stuck.getId(), LocalDateTime.now().minus(stuckAfter));
                    if (n == 1) {
                        audit(actor, stuck, action, TenantAuditPhase.RECLAIM, TenantStatus.PROVISIONING, TenantStatus.FAILED);
                    }
                    return n;
                });
                if (reclaimed != 1) {
                    return noOpNow(action, tenant.getId());
                }
                tenant = require(slug);
                log.warn("Platform admin {} reclaimed stuck PROVISIONING tenant {}", actor.id(), slug);
            }

            // Credentials: the verified signup row (BCrypt hash only). Never provision without a verified owner.
            TenantSignup signup = signupRepository.findByTenantId(tenant.getId()).orElse(null);
            if (signup == null || signup.getVerifiedAt() == null) {
                TenantActionResponse raced = lostRaceOrNull(action, tenant.getId());
                if (raced != null) {
                    return raced;
                }
                throw new IllegalTenantTransitionException(action, tenant.getStatus(),
                        signup == null ? ": no onboarding credentials on record"
                                : ": the owner has not verified their email");
            }
            ProvisioningRequest request = new ProvisioningRequest(tenant.getSlug(), tenant.getDisplayName(),
                    tenant.getOwnerEmail(), signup.getOwnerFullName(), signup.getPasswordHash());
            final Tenant claimed = tenant;
            // The status the claim actually moved from, captured INSIDE the claim transaction by the hook below (the
            // status read above may be stale if another caller changed it meanwhile).
            final java.util.concurrent.atomic.AtomicReference<TenantStatus> claimedFrom =
                    new java.util.concurrent.atomic.AtomicReference<>(tenant.getStatus()); // fallback until the hook runs
            try {
                // The saga performs the atomic PENDING/FAILED -> PROVISIONING claim; exactly one concurrent caller wins.
                // The INTENT row is written inside that claim transaction: only the winner writes it, it is committed
                // before any DDL runs, and if the insert fails the claim rolls back and nothing is provisioned.
                provisioningService.provision(request, t -> {
                    TenantStatus from = t.getStatus();
                    // The state machine must hold on the row the claim really moved, not only on the earlier read: e.g. an
                    // approve that read PENDING must not succeed through the claim if the row became FAILED meanwhile.
                    if (TenantStateMachine.decide(action, from) != Decision.TRANSITION) {
                        throw new IllegalTenantTransitionException(action, from, "");
                    }
                    claimedFrom.set(from);
                    audit(actor, claimed, action, TenantAuditPhase.INTENT, from, TenantStatus.PROVISIONING);
                });
            } catch (TenantAlreadyExistsException lost) {
                return noOpNow(action, claimed.getId());
            } catch (TenantProvisioningException failed) {
                // Best effort and never fatal: a failing audit write must not replace the generic 500 (the INTENT row
                // already records that the action was accepted). Log the class only, never the message or cause.
                bestEffort("outcome audit (FAILED)", claimed, () -> masterTx.executeWithoutResult(
                        s -> audit(actor, claimed, action, TenantAuditPhase.OUTCOME, claimedFrom.get(), TenantStatus.FAILED)));
                throw new TenantProvisioningFailedException(claimed.getSlug());
            } catch (InvalidProvisioningRequestException bad) {
                throw new TenantProvisioningFailedException(claimed.getSlug());
            } catch (org.springframework.dao.DataIntegrityViolationException hookFailure) {
                // Only the claim hook (INTENT insert) can surface this here: the saga maps its own races to
                // TenantAlreadyExistsException. It is a server fault with nothing provisioned, never a "lost race" 409.
                log.error("Tenant {}: claim failed ({}); nothing was provisioned", claimed.getSlug(),
                        hookFailure.getClass().getSimpleName());
                throw new org.springframework.dao.DataAccessResourceFailureException("tenant action could not be recorded");
            }
            // Saga committed ACTIVE. Two independent best-effort steps: dropping the password hash (retryable, repeated by
            // reconcile) and the OUTCOME audit row. Neither can undo or fail an action that already succeeded.
            bestEffort("signup hash delete", claimed, () -> masterTx.executeWithoutResult(
                    s -> signupRepository.deleteByTenantId(claimed.getId())));
            bestEffort("outcome audit (ACTIVE)", claimed, () -> masterTx.executeWithoutResult(
                    s -> audit(actor, claimed, action, TenantAuditPhase.OUTCOME, claimedFrom.get(), TenantStatus.ACTIVE)));
            log.info("Platform admin {} {} tenant {}", actor.id(), action, slug);
            return response(action, true, false, claimedFrom.get(), require(slug));
        } finally {
            originPolicy.invalidateAll();
        }
    }

    /**
     * A retried approve/reprovision on an ACTIVE tenant: repair what a crash after the saga may have left. In ONE
     * transaction, holding the tenant row lock:
     * <ol>
     *   <li>nothing is touched while the newest INTENT is younger than the grace period and has no OUTCOME (an in-flight
     *       winner is not raced);</li>
     *   <li>otherwise delete a leftover signup row (BCrypt hash);</li>
     *   <li>if a hash was deleted, or the newest INTENT has no later OUTCOME/RECONCILE row, append a RECONCILE row
     *       (ACTIVE -> ACTIVE) naming the retrying admin.</li>
     * </ol>
     * Nothing to repair: nothing written (plain idempotent no-op). A failure here never changes the response of the no-op.
     */
    private void reconcile(PlatformPrincipal actor, Tenant requested, TenantAction action) {
        bestEffort("reconcile", requested, () -> masterTx.executeWithoutResult(s -> {
            // PLAT-02c: serialise concurrent reconciles of one tenant on its row (SELECT ... FOR UPDATE) so two retries
            // cannot both see "no RECONCILE row yet" and write two; the loser re-reads the winner's committed rows
            Tenant tenant = tenantRepository.lockById(requested.getId()).orElse(null);
            if (tenant == null) {
                return;
            }
            TenantAuditLog intent = auditRepository
                    .findFirstByTenantIdAndPhaseOrderByIdDesc(tenant.getId(), TenantAuditPhase.INTENT).orElse(null);
            // Only an OUTCOME of the SAME action and starting status, written after that INTENT, completes it (a later
            // suspend/reactivate OUTCOME says nothing about this approve/reprovision).
            boolean outcomeWritten = intent != null && auditRepository
                    .existsByTenantIdAndPhaseAndActionAndPreviousStatusAndIdGreaterThan(tenant.getId(),
                            TenantAuditPhase.OUTCOME, intent.getAction(), intent.getPreviousStatus(), intent.getId());
            // In flight = the winner is probably still finishing: measured from the later of the INTENT and the tenant's
            // last status change, so a slow saga that only just went ACTIVE is not reconciled before its own OUTCOME.
            java.time.Instant last = intent == null ? null : intent.getOccurredAt();
            if (tenant.getUpdatedAt() != null) {
                java.time.Instant upd = tenant.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
                last = last == null || upd.isAfter(last) ? upd : last;
            }
            boolean inFlight = intent != null && !outcomeWritten && last != null
                    && last.isAfter(java.time.Instant.now().minus(reconcileGrace));
            if (inFlight) {
                return; // the winner of the saga is most likely still finishing its own steps: do not race it
            }
            int hashes = signupRepository.deleteByTenantId(tenant.getId());
            boolean outcomeMissing = intent != null && !outcomeWritten
                    && !auditRepository.existsByTenantIdAndPhaseAndIdGreaterThan(
                    tenant.getId(), TenantAuditPhase.RECONCILE, intent.getId());
            if (hashes > 0 || outcomeMissing) {
                audit(actor, tenant, action, TenantAuditPhase.RECONCILE, TenantStatus.ACTIVE, TenantStatus.ACTIVE);
                log.warn("Reconciled ACTIVE tenant {} (hashDeleted={}, outcomeMissing={})",
                        tenant.getSlug(), hashes > 0, outcomeMissing);
            }
        }));
    }

    /** Runs a step whose failure must not change the HTTP outcome; logs the exception CLASS only (no message/cause). */
    private void bestEffort(String what, Tenant tenant, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.error("Tenant {}: {} failed ({}); the INTENT row and reconciliation cover it", tenant.getSlug(), what,
                    e.getClass().getSimpleName());
        }
    }

    private boolean isStuck(Tenant t) {
        return t.getStatus() == TenantStatus.PROVISIONING && t.getUpdatedAt() != null
                && t.getUpdatedAt().isBefore(LocalDateTime.now().minus(stuckAfter));
    }

    /** After losing a race: the tenant is where the winner took it; report that as an idempotent no-op. */
    private TenantActionResponse noOpNow(TenantAction action, Long id) {
        TenantActionResponse r = lostRaceOrNull(action, id);
        if (r != null) {
            return r;
        }
        Tenant now = tenantRepository.findById(id).orElseThrow(() -> notFound());
        throw new IllegalTenantTransitionException(action, now.getStatus(), "");
    }

    private TenantActionResponse lostRaceOrNull(TenantAction action, Long id) {
        Tenant now = tenantRepository.findById(id).orElseThrow(() -> notFound());
        if (now.getStatus() == TenantStatus.PROVISIONING || now.getStatus() == TenantStatus.ACTIVE) {
            return response(action, false, now.getStatus() == TenantStatus.PROVISIONING, now.getStatus(), now);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void audit(PlatformPrincipal actor, Tenant t, TenantAction action, TenantStatus prev, TenantStatus next) {
        audit(actor, t, action, TenantAuditPhase.OUTCOME, prev, next);
    }

    private void audit(PlatformPrincipal actor, Tenant t, TenantAction action, TenantAuditPhase phase,
                       TenantStatus prev, TenantStatus next) {
        auditRepository.saveAndFlush(TenantAuditLog.builder().phase(phase)
                .actorAdminId(actor.id()).actorEmail(actor.email())
                .tenantId(t.getId()).tenantSlug(t.getSlug())
                .action(action).previousStatus(prev).newStatus(next).build());
    }

    private void throttle(PlatformPrincipal actor) {
        long wait = actionLimiter.tryAcquire("admin:" + actor.id());
        if (wait != 0) {
            throw new RateLimitExceededException("Too many tenant actions, slow down", wait);
        }
    }

    /** Unknown and malformed slugs are indistinguishable: same 404, same message, no schema or SQL involved. */
    private Tenant require(String slug) {
        if (slug == null || !SLUG_SHAPE.matcher(slug).matches()) {
            throw notFound();
        }
        return tenantRepository.findBySlug(slug).orElseThrow(PlatformTenantService::notFound);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Tenant not found");
    }

    private TenantActionResponse response(TenantAction action, boolean changed, boolean inProgress,
                                          TenantStatus previous, Tenant tenant) {
        return new TenantActionResponse(action.name(), changed, inProgress, previous.name(), summary(tenant));
    }

    /** PLAN-01: the summary carries the plan code and name, read from master.plans by the tenant's plan id. */
    private PlatformTenantSummary summary(Tenant t) {
        Plan plan = planRepository.findById(t.getPlanId())
                .orElseThrow(() -> new IllegalStateException("tenant plan row missing"));
        return PlatformTenantSummary.from(t, plan);
    }

    /** One plan query per page, not per row. */
    private List<PlatformTenantSummary> summaries(List<Tenant> page) {
        Map<Long, Plan> byId = new java.util.HashMap<>();
        planRepository.findAllById(page.stream().map(Tenant::getPlanId).distinct().toList())
                .forEach(p -> byId.put(p.getId(), p));
        return page.stream().map(t -> PlatformTenantSummary.from(t, byId.get(t.getPlanId()))).toList();
    }

    // ------------------------------------------------------------------------------------------ plan (PLAN-01)

    static final String TRIAL_RANGE_MESSAGE = "must be later than the current trial end and at most 365 days ahead";

    /**
     * {@code PUT /platform/tenants/{slug}/plan} (plans.md section 11): the manual bridge until PLAN-06 billing. Same slug
     * rule and per-admin throttle as the lifecycle actions. In ONE master transaction holding the tenant row lock: the
     * plan / trial / status write, one plan audit row ({@code TENANT_PLAN_UPDATE}) and one tenant audit row
     * ({@code CHANGE_PLAN}, OUTCOME, previous = new registry status); {@link TenantSubscriptionChangedEvent} is published in
     * it so the PLAN-02 cache evicts after commit. A request that changes nothing writes nothing.
     */
    public PlatformTenantSummary changePlan(String slug, TenantPlanUpdateRequest req, PlatformPrincipal actor) {
        throttle(actor);
        Tenant initial = require(slug);
        validateShape(req);
        return masterTx.execute(s -> {
            Tenant t = tenantRepository.lockById(initial.getId()).orElseThrow(PlatformTenantService::notFound);
            Plan before = planRepository.findById(t.getPlanId())
                    .orElseThrow(() -> new IllegalStateException("tenant plan row missing"));
            Plan after = before;
            if (req.planId() != null) {
                after = planRepository.findById(req.planId().longValue())
                        .orElseThrow(() -> new PlanValidationException("planId", "unknown plan"));
                if (!after.isActive() && !Boolean.TRUE.equals(req.allowInactive())) {
                    throw PlanErrors.inactive();
                }
            }
            SubscriptionStatus status = t.getSubscriptionStatus();
            Instant trialEnd = t.getTrialEndsAt();
            if (req.trialEndsAt() != null) {
                if (status != SubscriptionStatus.TRIALING && status != SubscriptionStatus.CANCELED) {
                    throw new PlanValidationException("trialEndsAt",
                            "can be extended only while the subscription is TRIALING or CANCELED");
                }
                Instant now = Instant.now();
                Instant wanted = req.trialEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                Instant floor = trialEnd == null || trialEnd.isBefore(now) ? now : trialEnd;
                if (!wanted.isAfter(floor) || wanted.isAfter(now.plus(Duration.ofDays(365)))) {
                    throw new PlanValidationException("trialEndsAt", TRIAL_RANGE_MESSAGE);
                }
                trialEnd = wanted;
                status = SubscriptionStatus.TRIALING; // a canceled or expired trial is revived
            }
            if (req.subscriptionStatus() != null) {
                status = SubscriptionStatus.valueOf(req.subscriptionStatus());
            }
            com.fasterxml.jackson.databind.node.ArrayNode changes = PLAN_JSON.createArrayNode();
            if (!after.getId().equals(before.getId())) {
                changes.add(planChange("planCode", before.getCode(), after.getCode()));
            }
            if (!java.util.Objects.equals(trialEnd, t.getTrialEndsAt())) {
                changes.add(planChange("trialEndsAt", t.getTrialEndsAt() == null ? null : t.getTrialEndsAt().toString(),
                        trialEnd.toString()));
            }
            if (status != t.getSubscriptionStatus()) {
                changes.add(planChange("subscriptionStatus", t.getSubscriptionStatus().name(), status.name()));
            }
            if (changes.isEmpty()) {
                return summary(t); // nothing changes: nothing written
            }
            tenantRepository.updateSubscription(t.getId(), after.getId(), status, trialEnd);
            planAuditRepository.saveAndFlush(PlanAuditLog.builder()
                    .actorAdminId(actor.id()).actorEmail(actor.email()).action(PlanAuditAction.TENANT_PLAN_UPDATE)
                    .planId(after.getId()).planCode(after.getCode())
                    .tenantId(t.getId()).tenantSlug(t.getSlug()).changes(changes).build());
            audit(actor, t, TenantAction.CHANGE_PLAN, TenantAuditPhase.OUTCOME, t.getStatus(), t.getStatus());
            events.publishEvent(new TenantSubscriptionChangedEvent(t.getId()));
            log.info("Platform admin {} changed the plan/subscription of tenant {}", actor.id(), t.getSlug());
            return summary(tenantRepository.findById(t.getId()).orElseThrow(PlatformTenantService::notFound));
        });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper PLAN_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.node.ObjectNode planChange(String field, String before, String after) {
        com.fasterxml.jackson.databind.node.ObjectNode n = PLAN_JSON.createObjectNode();
        n.put("field", field);
        n.put("before", before);
        n.put("after", after);
        return n;
    }

    /** The combination rules of section 11 (400 with a field map), checked before any lookup. */
    private static void validateShape(TenantPlanUpdateRequest req) {
        if (req.planId() == null && req.trialEndsAt() == null && req.subscriptionStatus() == null) {
            throw new PlanValidationException("planId", "send at least one of planId, trialEndsAt or subscriptionStatus");
        }
        if (req.trialEndsAt() != null && req.subscriptionStatus() != null) {
            throw new PlanValidationException("subscriptionStatus", "cannot be combined with trialEndsAt");
        }
        if (req.allowInactive() != null && req.planId() == null) {
            throw new PlanValidationException("allowInactive", "allowed only together with planId");
        }
        if (req.subscriptionStatus() != null && !req.subscriptionStatus().equals("ACTIVE")
                && !req.subscriptionStatus().equals("CANCELED")) {
            throw new PlanValidationException("subscriptionStatus", "must be ACTIVE or CANCELED");
        }
    }
}

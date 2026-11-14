package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.PlatformTenantSummary;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * saga (which binds and restores its own tenant context) for approve/reprovision. Never reads a tenant schema.
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
    private final TransactionTemplate masterTx;
    private final TokenBucketRateLimiter actionLimiter;
    private final Duration stuckAfter;
    /** An INTENT younger than this is presumed still in flight, so a retry does not write a RECONCILE row for it. */
    private final Duration reconcileGrace;

    public PlatformTenantService(TenantRepository tenantRepository, TenantSignupRepository signupRepository,
                                 TenantAuditLogRepository auditRepository,
                                 TenantProvisioningService provisioningService, TenantOriginPolicy originPolicy,
                                 PlatformTransactionManager txManager,
                                 @Value("${platform.tenants.actions-per-minute:30}") int actionsPerMinute,
                                 @Value("${platform.tenants.stuck-provisioning-minutes:15}") long stuckMinutes,
                                 @Value("${platform.tenants.reconcile-grace-seconds:60}") long reconcileGraceSeconds) {
        this.tenantRepository = tenantRepository;
        this.signupRepository = signupRepository;
        this.auditRepository = auditRepository;
        this.provisioningService = provisioningService;
        this.originPolicy = originPolicy;
        this.masterTx = new TransactionTemplate(txManager);
        this.actionLimiter = new TokenBucketRateLimiter(Math.max(1, actionsPerMinute), Duration.ofMinutes(1));
        this.stuckAfter = Duration.ofMinutes(Math.max(1, stuckMinutes));
        this.reconcileGrace = Duration.ofSeconds(Math.max(0, reconcileGraceSeconds));
    }

    // ------------------------------------------------------------------------------------------ list

    public PlatformTenantPage list(String statusFilter, Integer page, Integer size, String sortKey, String direction) {
        TenantStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                status = TenantStatus.valueOf(statusFilter.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown status filter");
            }
        }
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (size != null && size < 1) {
            throw new BadRequestException("size must be >= 1");
        }
        int effectiveSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String key = sortKey == null || sortKey.isBlank() ? "createdAt" : sortKey.strip();
        String property = SORT_FIELDS.get(key);
        if (property == null) {
            throw new BadRequestException("Unsupported sort field");
        }
        Sort.Direction dir;
        if (direction == null || direction.isBlank()) {
            dir = Sort.Direction.DESC;
        } else if (direction.equalsIgnoreCase("asc")) {
            dir = Sort.Direction.ASC;
        } else if (direction.equalsIgnoreCase("desc")) {
            dir = Sort.Direction.DESC;
        } else {
            throw new BadRequestException("direction must be asc or desc");
        }
        // The unique id is always the final tiebreaker so equal sort keys never reorder between pages.
        PageRequest pageable = PageRequest.of(p, effectiveSize, Sort.by(dir, property).and(Sort.by(dir, "id")));
        Page<Tenant> result = status == null
                ? tenantRepository.findAll(pageable)
                : tenantRepository.findByStatus(status, pageable);
        List<PlatformTenantSummary> items = result.getContent().stream().map(PlatformTenantSummary::from).toList();
        return new PlatformTenantPage(items, p, effectiveSize, result.getTotalElements(), result.getTotalPages(),
                key, dir.name().toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------------------------------ suspend / reactivate

    public TenantActionResponse suspend(String slug, PlatformPrincipal actor) {
        return flip(slug, actor, TenantAction.SUSPEND);
    }

    public TenantActionResponse reactivate(String slug, PlatformPrincipal actor) {
        return flip(slug, actor, TenantAction.REACTIVATE);
    }

    private TenantActionResponse flip(String slug, PlatformPrincipal actor, TenantAction action) {
        throttle(actor);
        Tenant initial = require(slug);
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
                    tenant.getOwnerEmail(), signup.getOwnerFullName(), signup.getPasswordHash(), tenant.getPlan());
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
     * transaction: delete a leftover signup row (BCrypt hash) and, if a hash was deleted or the newest INTENT has no later
     * OUTCOME/RECONCILE row; nothing is touched while the newest INTENT is younger than the grace period without an OUTCOME (an in-flight winner is not raced), append a RECONCILE row
     * (ACTIVE -> ACTIVE) naming the retrying admin. Nothing to repair: nothing written (plain idempotent no-op).
     * A failure here never changes the response of the no-op.
     */
    private void reconcile(PlatformPrincipal actor, Tenant tenant, TenantAction action) {
        bestEffort("reconcile", tenant, () -> masterTx.executeWithoutResult(s -> {
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

    private static TenantActionResponse response(TenantAction action, boolean changed, boolean inProgress,
                                                 TenantStatus previous, Tenant tenant) {
        return new TenantActionResponse(action.name(), changed, inProgress, previous.name(),
                PlatformTenantSummary.from(tenant));
    }
}

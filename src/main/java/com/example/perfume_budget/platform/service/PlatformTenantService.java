package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.PlatformTenantSummary;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
import com.example.perfume_budget.platform.enums.TenantAction;
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
 * approve/reprovision the saga itself commits in separate steps, so exactly one audit row is written afterwards by the
 * caller that won the saga's claim (previous status to the final ACTIVE/FAILED status).
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

    public PlatformTenantService(TenantRepository tenantRepository, TenantSignupRepository signupRepository,
                                 TenantAuditLogRepository auditRepository,
                                 TenantProvisioningService provisioningService, TenantOriginPolicy originPolicy,
                                 PlatformTransactionManager txManager,
                                 @Value("${platform.tenants.actions-per-minute:30}") int actionsPerMinute,
                                 @Value("${platform.tenants.stuck-provisioning-minutes:15}") long stuckMinutes) {
        this.tenantRepository = tenantRepository;
        this.signupRepository = signupRepository;
        this.auditRepository = auditRepository;
        this.provisioningService = provisioningService;
        this.originPolicy = originPolicy;
        this.masterTx = new TransactionTemplate(txManager);
        this.actionLimiter = new TokenBucketRateLimiter(Math.max(1, actionsPerMinute), Duration.ofMinutes(1));
        this.stuckAfter = Duration.ofMinutes(Math.max(1, stuckMinutes));
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
                return response(action, false, tenant.getStatus() == TenantStatus.PROVISIONING,
                        tenant.getStatus(), tenant);
            }
            if (d == Decision.NO_OP) {
                // Stuck PROVISIONING (saga died): compare-and-set it to FAILED; only one caller wins and proceeds.
                final Long stuckId = tenant.getId();
                int reclaimed = masterTx.execute(s -> tenantRepository.reclaimStuckProvisioning(stuckId,
                        LocalDateTime.now().minus(stuckAfter)));
                if (reclaimed != 1) {
                    return noOpNow(action, tenant.getId());
                }
                tenant = require(slug);
                log.warn("Platform admin {} reclaimed stuck PROVISIONING tenant {}", actor.id(), slug);
            }
            TenantStatus previous = tenant.getStatus(); // PENDING (approve) or FAILED (reprovision / reclaimed)

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
            try {
                // The saga performs the atomic PENDING/FAILED -> PROVISIONING claim; exactly one concurrent caller wins.
                provisioningService.provision(request);
            } catch (TenantAlreadyExistsException lost) {
                return noOpNow(action, claimed.getId());
            } catch (TenantProvisioningException failed) {
                masterTx.executeWithoutResult(s -> audit(actor, claimed, action, previous, TenantStatus.FAILED));
                throw new TenantProvisioningFailedException(claimed.getSlug());
            } catch (InvalidProvisioningRequestException bad) {
                throw new TenantProvisioningFailedException(claimed.getSlug());
            }
            masterTx.executeWithoutResult(s -> {
                signupRepository.deleteByTenantId(claimed.getId()); // drops the password hash, like onboarding does
                audit(actor, claimed, action, previous, TenantStatus.ACTIVE);
            });
            log.info("Platform admin {} {} tenant {}", actor.id(), action, slug);
            return response(action, true, false, previous, require(slug));
        } finally {
            originPolicy.invalidateAll();
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
        auditRepository.saveAndFlush(TenantAuditLog.builder()
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

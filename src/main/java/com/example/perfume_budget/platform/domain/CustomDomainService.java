package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.exception.RetryAfterApiCodeException;
import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantDomainAuditLog;
import com.example.perfume_budget.platform.repository.TenantDomainAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import com.example.perfume_budget.security.cors.CorsProperties;
import com.example.perfume_budget.security.cors.PlatformCorsProperties;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import com.example.perfume_budget.utils.DbErrorDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DOMAIN-02: the only code path that writes {@code master.tenants.custom_domain}.
 *
 * <ul>
 *   <li><b>request</b> (tenant admin): validate + normalise ({@link CustomDomains}), claim it as PENDING with a fresh
 *       random token. Unique across tenants case-insensitively; the database index {@value #UNIQUE_INDEX} is the final
 *       judge, so two tenants racing for one domain get one winner and one clean {@code 409 DOMAIN_TAKEN}. Another
 *       tenant's PENDING/FAILED claim is released first when both its claim time and the last time it was VERIFIED are older
 *       than {@code unverified-claim-ttl-hours} (anti-squatting, audited as RELEASE by SYSTEM); a VERIFIED domain is
 *       never taken over, and one that just failed a re-verification keeps its protection for the full TTL.</li>
 *   <li><b>verify</b> (tenant admin or platform admin): DNS TXT lookup of {@code <txt-label>.<domain>} OUTSIDE any
 *       transaction (no row lock is held across network I/O), then the result is applied under the row lock only if the
 *       claim (domain + token) is still the one that was checked. Found: VERIFIED; definitively absent: FAILED (also a
 *       VERIFIED domain whose record disappeared: re-verification revokes it); no definitive answer: nothing changes,
 *       {@code 503 DOMAIN_DNS_UNAVAILABLE}. The stored domain is re-validated first, so a legacy value that breaks the
 *       AC1 rules can never become VERIFIED. Rate limited per tenant and globally.</li>
 *   <li><b>remove</b> (tenant admin or platform admin): clears the domain and its verification state.</li>
 * </ul>
 * Every change writes a row to {@code master.tenant_domain_audit_log} in the same transaction, and every write calls
 * {@link TenantOriginPolicy#invalidateAll()} AFTER the transaction ends, so the CORS decision cache never keeps
 * honouring a domain that stopped being VERIFIED (tenant resolution reads the registry per request; no cache).
 */
@Slf4j
@Service
public class CustomDomainService {

    public static final String UNIQUE_INDEX = "uq_tenants_custom_domain_lower";
    public static final String TAKEN = "DOMAIN_TAKEN";
    public static final String NOT_SET = "DOMAIN_NOT_SET";
    public static final String BUSY = "CUSTOM_DOMAIN_BUSY";
    public static final String VERIFY_RATE_LIMITED = "DOMAIN_VERIFY_RATE_LIMITED";
    public static final String CHANGE_RATE_LIMITED = "DOMAIN_CHANGE_RATE_LIMITED";
    public static final String DNS_UNAVAILABLE = "DOMAIN_DNS_UNAVAILABLE";
    /** {@code failureReason} / audit detail when the TXT record was definitively absent or different. */
    public static final String TXT_NOT_FOUND = "TXT_RECORD_NOT_FOUND";
    static final String TXT_MATCHED = "TXT_RECORD_MATCHED";
    static final String RELEASED_DETAIL = "UNVERIFIED_CLAIM_EXPIRED";
    static final int HISTORY_SIZE = 50;
    static final long DNS_RETRY_AFTER_SECONDS = 30;
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9-]{3,40}$");

    public enum ActorType { TENANT_ADMIN, PLATFORM_ADMIN, SYSTEM }

    /** Who acts: a tenant's admin user, a platform super-admin, or the system (release of a stale claim). */
    public record Actor(ActorType type, Long id, String email) {
        static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null, null);
    }

    private final TenantRepository tenantRepository;
    private final TenantDomainAuditLogRepository auditRepository;
    private final TenantOriginPolicy originPolicy;
    private final DbLockTimeout lockTimeout;
    private final DnsTxtResolver dns;
    private final CustomDomainProperties props;
    private final CustomDomains.Policy policy;
    private final TransactionTemplate masterTx;
    private final TokenBucketRateLimiter verifyLimiter;
    private final TokenBucketRateLimiter globalVerifyLimiter;
    private final TokenBucketRateLimiter changeLimiter;
    private final SecureRandom random = new SecureRandom();
    private volatile Clock clock = Clock.systemUTC();

    public CustomDomainService(TenantRepository tenantRepository, TenantDomainAuditLogRepository auditRepository,
                               TenantOriginPolicy originPolicy, DbLockTimeout lockTimeout, DnsTxtResolver dns,
                               CustomDomainProperties props, TenantResolutionProperties tenantProps,
                               CorsProperties corsProps, PlatformCorsProperties platformCorsProps,
                               PlatformTransactionManager txManager) {
        this.tenantRepository = tenantRepository;
        this.auditRepository = auditRepository;
        this.originPolicy = originPolicy;
        this.lockTimeout = lockTimeout;
        this.dns = dns;
        this.props = props;
        List<String> staticHosts = new ArrayList<>();
        addHosts(staticHosts, corsProps.getAllowedOrigins());
        addHosts(staticHosts, platformCorsProps.getAllowedOrigins());
        // rework L2: the platform's own CNAME target is a platform name too (never claimable by a tenant)
        String cname = props.getCnameTarget() == null ? "" : props.getCnameTarget().strip();
        if (!cname.isEmpty()) {
            staticHosts.add(cname);
        }
        this.policy = CustomDomains.Policy.of(tenantProps.getBaseDomain(), tenantProps.getPlatformDomains(), staticHosts,
                props.getReservedSuffixes());
        this.masterTx = new TransactionTemplate(txManager);
        this.verifyLimiter = new TokenBucketRateLimiter(Math.max(1, props.getVerifyPerTenantPerHour()), Duration.ofHours(1));
        this.globalVerifyLimiter = new TokenBucketRateLimiter(Math.max(1, props.getVerifyGlobalPerMinute()), Duration.ofMinutes(1));
        this.changeLimiter = new TokenBucketRateLimiter(Math.max(1, props.getChangesPerTenantPerHour()), Duration.ofHours(1));
    }

    private static void addHosts(List<String> out, List<String> origins) {
        if (origins == null) {
            return;
        }
        for (String o : origins) {
            TenantOriginPolicy.ParsedOrigin p = TenantOriginPolicy.parse(o == null ? null : o.strip());
            if (p != null) {
                out.add(p.host());
            }
        }
    }

    /** The validation policy built from configuration (base domain, platform domains, static CORS hosts). */
    public CustomDomains.Policy policy() {
        return policy;
    }

    /** Test support: a fixed clock for the stale-claim cutoff. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Test support: refills every rate-limit bucket. Production never calls it. */
    void resetLimits() {
        verifyLimiter.reset();
        globalVerifyLimiter.reset();
        changeLimiter.reset();
    }

    // ---------------------------------------------------------------------------------------------------- reads

    public CustomDomainView get(String slug) {
        return view(tenant(slug));
    }

    public PlatformCustomDomainView platformView(String slug) {
        Tenant t = tenant(slug);
        List<CustomDomainAuditRow> history = auditRepository
                .findByTenantIdOrderByIdDesc(t.getId(), PageRequest.of(0, HISTORY_SIZE)).stream()
                .map(CustomDomainAuditRow::from).toList();
        return new PlatformCustomDomainView(t.getSlug(), view(t), history);
    }

    // ---------------------------------------------------------------------------------------------------- request

    public CustomDomainView request(String slug, String rawDomain, Actor actor) {
        Tenant initial = tenant(slug);
        limit(changeLimiter, "c:" + initial.getId(), CHANGE_RATE_LIMITED, "Too many custom-domain changes. Try again later.");
        String domain = CustomDomains.normalize(rawDomain, policy);
        Long id = initial.getId();
        try {
            CustomDomainView v = inLockedTx(() -> {
                Tenant t = lock(id);
                if (domain.equals(t.getCustomDomain())) {
                    return view(t); // idempotent: same claim, same token (the published TXT record stays valid)
                }
                Tenant owner = tenantRepository.findCustomDomainClaim(domain).orElse(null);
                if (owner != null && !owner.getId().equals(id)) {
                    Instant cutoff = clock.instant().minus(Duration.ofHours(Math.max(1, props.getUnverifiedClaimTtlHours())));
                    String ownerStatus = name(owner.getCustomDomainStatus());
                    if (tenantRepository.releaseStaleClaim(owner.getId(), domain, cutoff) != 1) {
                        throw taken();
                    }
                    audit(Actor.SYSTEM, owner.getId(), owner.getSlug(), "RELEASE", domain, null, ownerStatus, null,
                            RELEASED_DETAIL);
                    t = lock(id); // the bulk update cleared the persistence context
                }
                String previousDomain = t.getCustomDomain();
                String previousStatus = name(t.getCustomDomainStatus());
                Instant now = clock.instant();
                t.setCustomDomain(domain);
                t.setCustomDomainStatus(CustomDomainStatus.PENDING);
                t.setCustomDomainToken(newToken());
                t.setCustomDomainRequestedAt(now);
                t.setCustomDomainCheckedAt(null);
                t.setCustomDomainVerifiedAt(null);
                t.setCustomDomainLastVerifiedAt(null); // a new claim starts its own history
                t.setCustomDomainFailure(null);
                tenantRepository.saveAndFlush(t);
                audit(actor, t.getId(), t.getSlug(), "REQUEST", domain, previousDomain, previousStatus,
                        CustomDomainStatus.PENDING.name(), previousDomain != null ? "REPLACED" : null);
                return view(t);
            });
            log.info("Custom domain requested for tenant {} by {} {}", slug, actor.type(), actor.id());
            return v;
        } catch (DataIntegrityViolationException e) {
            if (UNIQUE_INDEX.equals(DbErrorDetails.of(e).constraint())) {
                throw taken(); // lost the race to a concurrent claim of the same domain
            }
            throw e;
        } finally {
            originPolicy.invalidateAll(); // after the transaction ended (commit or rollback), never before
        }
    }

    // ---------------------------------------------------------------------------------------------------- verify

    public CustomDomainView verify(String slug, Actor actor) {
        Tenant snapshot = tenant(slug);
        if (snapshot.getCustomDomain() == null) {
            throw new ApiCodeException(HttpStatus.NOT_FOUND, NOT_SET, "No custom domain is set.");
        }
        String bucket = (actor.type() == ActorType.PLATFORM_ADMIN ? "p:" : "t:") + snapshot.getId();
        limit(verifyLimiter, bucket, VERIFY_RATE_LIMITED, "Too many verification attempts. Try again later.");
        limit(globalVerifyLimiter, "global", VERIFY_RATE_LIMITED, "Too many verification attempts. Try again later.");

        String domain = snapshot.getCustomDomain();
        String token = snapshot.getCustomDomainToken();
        String failure = revalidate(domain);
        if (failure == null) {
            failure = txtProofFound(domain, token) ? null : TXT_NOT_FOUND;
        }
        final String outcome = failure;
        try {
            return inLockedTx(() -> {
                Tenant t = lock(snapshot.getId());
                if (!Objects.equals(domain, t.getCustomDomain()) || !Objects.equals(token, t.getCustomDomainToken())) {
                    return view(t); // the claim changed while DNS was queried: that answer is not about this claim
                }
                String previous = name(t.getCustomDomainStatus());
                Instant now = clock.instant();
                CustomDomainStatus next = outcome == null ? CustomDomainStatus.VERIFIED : CustomDomainStatus.FAILED;
                boolean wasVerified = t.getCustomDomainStatus() == CustomDomainStatus.VERIFIED;
                t.setCustomDomainStatus(next);
                t.setCustomDomainCheckedAt(now);
                t.setCustomDomainFailure(outcome);
                if (next == CustomDomainStatus.VERIFIED) {
                    t.setCustomDomainVerifiedAt(now);
                    t.setCustomDomainLastVerifiedAt(now);
                } else {
                    t.setCustomDomainVerifiedAt(null);
                    if (wasVerified) {
                        // last_verified_at = the last time the domain was SEEN verified (F1b): a domain that stops being
                        // VERIFIED now is protected for the full TTL from now. PENDING/FAILED -> FAILED leaves it alone,
                        // so a claim that keeps failing cannot extend its own protection.
                        t.setCustomDomainLastVerifiedAt(now);
                    }
                }
                tenantRepository.saveAndFlush(t);
                audit(actor, t.getId(), t.getSlug(), "VERIFY", domain, null, previous, next.name(),
                        outcome == null ? TXT_MATCHED : outcome);
                log.info("Custom domain check for tenant {}: {} -> {}", t.getSlug(), previous, next);
                return view(t);
            });
        } finally {
            originPolicy.invalidateAll();
        }
    }

    /** Null when the stored domain still passes the AC1 rules in its canonical form, else the rejection code. */
    private String revalidate(String stored) {
        try {
            return CustomDomains.normalize(stored, policy).equals(stored) ? null : CustomDomains.INVALID;
        } catch (CustomDomainRejectedException e) {
            return e.getCode();
        }
    }

    private boolean txtProofFound(String domain, String token) {
        String expected = props.getTxtValuePrefix() + token;
        try {
            return dns.lookupTxt(txtName(domain)).stream()
                    .anyMatch(v -> v != null && v.strip().equals(expected));
        } catch (DnsTxtResolver.DnsLookupException e) {
            // no exception text in the log (SEC-01d/e): the resolver's cause is a fixed class name anyway
            log.warn("Custom domain TXT lookup gave no definitive answer");
            throw new RetryAfterApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, DNS_UNAVAILABLE,
                    "DNS did not answer. Try again shortly.", DNS_RETRY_AFTER_SECONDS);
        }
    }

    // ---------------------------------------------------------------------------------------------------- remove

    public CustomDomainView remove(String slug, Actor actor) {
        Tenant initial = tenant(slug);
        if (actor.type() == ActorType.TENANT_ADMIN) {
            limit(changeLimiter, "c:" + initial.getId(), CHANGE_RATE_LIMITED, "Too many custom-domain changes. Try again later.");
        }
        try {
            return inLockedTx(() -> {
                Tenant t = lock(initial.getId());
                if (t.getCustomDomain() == null) {
                    return view(t); // idempotent
                }
                String domain = t.getCustomDomain();
                String previous = name(t.getCustomDomainStatus());
                t.clearCustomDomain();
                tenantRepository.saveAndFlush(t);
                audit(actor, t.getId(), t.getSlug(), "REMOVE", domain, null, previous, null, null);
                log.info("Custom domain removed for tenant {} by {} {}", t.getSlug(), actor.type(), actor.id());
                return view(t);
            });
        } finally {
            originPolicy.invalidateAll();
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    public CustomDomainView view(Tenant t) {
        String cname = props.getCnameTarget() == null || props.getCnameTarget().isBlank() ? null : props.getCnameTarget().strip();
        if (t.getCustomDomain() == null) {
            return new CustomDomainView(null, null, null, null, cname, null, null, null, null);
        }
        return new CustomDomainView(t.getCustomDomain(), name(t.getCustomDomainStatus()), txtName(t.getCustomDomain()),
                props.getTxtValuePrefix() + t.getCustomDomainToken(), cname, t.getCustomDomainRequestedAt(),
                t.getCustomDomainCheckedAt(), t.getCustomDomainVerifiedAt(), t.getCustomDomainFailure());
    }

    String txtName(String domain) {
        return props.getTxtLabel() + "." + domain;
    }

    private Tenant tenant(String slug) {
        if (slug == null || !SLUG_SHAPE.matcher(slug).matches()) {
            throw new ResourceNotFoundException("Tenant not found");
        }
        return tenantRepository.findBySlug(slug).orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    private Tenant lock(Long id) {
        return tenantRepository.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    /** One master transaction whose row-lock waits are bounded (503 {@value #BUSY} on timeout). */
    private <T> T inLockedTx(java.util.function.Supplier<T> body) {
        return masterTx.execute(status -> lockTimeout.run(BUSY,
                "The custom domain is being changed right now. Please try again.", body));
    }

    private void audit(Actor actor, Long tenantId, String tenantSlug, String action, String domain,
                       String previousDomain, String previousStatus, String newStatus, String detail) {
        auditRepository.save(TenantDomainAuditLog.builder()
                .actorType(actor.type().name()).actorId(actor.id()).actorEmail(actor.email())
                .tenantId(tenantId).tenantSlug(tenantSlug).action(action).domain(domain)
                .previousDomain(previousDomain).previousStatus(previousStatus).newStatus(newStatus).detail(detail)
                .build());
    }

    private static void limit(TokenBucketRateLimiter limiter, String key, String code, String message) {
        long wait = limiter.tryAcquire(key);
        if (wait != 0) {
            throw new RetryAfterApiCodeException(HttpStatus.TOO_MANY_REQUESTS, code, message, wait);
        }
    }

    private static ApiCodeException taken() {
        return new ApiCodeException(HttpStatus.CONFLICT, TAKEN, "This domain is already claimed by another shop.");
    }

    private String newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}

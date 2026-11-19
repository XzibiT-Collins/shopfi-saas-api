package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantDomainAuditLog;
import com.example.perfume_budget.platform.repository.TenantDomainAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.service.DbLockTimeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * DOMAIN-03: re-checks the DNS TXT proof of VERIFIED custom domains, so a domain whose owner let it lapse (and that
 * someone else may now control) stops being trusted for CORS, tenant resolution and OAuth2 redirects.
 *
 * <p>Design (docs/api/custom-domains.md, "Scheduled re-verification"):
 * <ul>
 *   <li><b>Row claim, no tenant context.</b> The job runs without a {@code TenantContext} and touches only
 *       {@code master.tenants} / {@code master.tenant_domain_audit_log}. Each domain is claimed with one atomic
 *       {@code UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING}, which pushes the row's due
 *       time forward by the lease: concurrent instances never check the same row, no row lock is held while DNS is
 *       queried, and rows of a crashed instance come back when the lease ends.</li>
 *   <li><b>Bounded.</b> At most {@code batch-size} domains per round, each needing a token of the job's own DNS bucket
 *       and of the global verify bucket shared with manual verifies (60/min); a round stops at the first refusal.</li>
 *   <li><b>No downgrade on doubt.</b> A DNS error (timeout/SERVFAIL) changes nothing (retry later). A definite miss
 *       (answer without the exact proof) only counts; VERIFIED becomes FAILED once {@code misses-required} consecutive
 *       definite misses span at least {@code grace}. A found proof resets the streak. A stored domain that no longer
 *       passes the validation rules is downgraded at once (local rule, no DNS involved).</li>
 *   <li><b>Downgrade</b> = FAILED, {@code last_verified_at} stamped now (F1b rule), audit row {@code REVERIFY} by
 *       SYSTEM, then {@link TenantOriginPolicy#invalidateAll()} after the transaction ended.</li>
 * </ul>
 * Never logs a token or DNS error text.
 */
@Slf4j
@Component
public class CustomDomainReverifier {

    public static final String ACTION = "REVERIFY";

    /** Outcome of one round, for logs and tests. */
    public record RoundResult(int claimed, int stillVerified, int misses, int dnsErrors, int downgraded, boolean budgetStop) {}

    private record Claim(long id, String slug, String domain, String token, Timestamp previousDue, Timestamp leaseEnd) {}

    private enum Dns { FOUND, MISSING, UNKNOWN }

    private final JdbcTemplate jdbc;
    private final TenantRepository tenantRepository;
    private final TenantDomainAuditLogRepository auditRepository;
    private final TenantOriginPolicy originPolicy;
    private final DbLockTimeout lockTimeout;
    private final DnsTxtResolver dns;
    private final CustomDomainService service;
    private final CustomDomainProperties domainProps;
    private final CustomDomainReverifyProperties props;
    private final ReverifySchedule schedule;
    private final TransactionTemplate masterTx;
    private volatile TokenBucketRateLimiter jobDnsLimiter;
    private volatile Clock clock = Clock.systemUTC();

    public CustomDomainReverifier(JdbcTemplate jdbc, TenantRepository tenantRepository,
                                  TenantDomainAuditLogRepository auditRepository, TenantOriginPolicy originPolicy,
                                  DbLockTimeout lockTimeout, DnsTxtResolver dns, CustomDomainService service,
                                  CustomDomainProperties domainProps, CustomDomainReverifyProperties props,
                                  ReverifySchedule schedule, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tenantRepository = tenantRepository;
        this.auditRepository = auditRepository;
        this.originPolicy = originPolicy;
        this.lockTimeout = lockTimeout;
        this.dns = dns;
        this.service = service;
        this.domainProps = domainProps;
        this.props = props;
        this.schedule = schedule;
        this.masterTx = new TransactionTemplate(txManager);
        this.jobDnsLimiter = new TokenBucketRateLimiter(Math.max(1, props.getDnsPerMinute()), Duration.ofMinutes(1));
    }

    /** Test support: fixed clock (also used for the claim query, so it is fully deterministic). */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Test support: replaces the job's own DNS bucket by one with this capacity per minute. */
    void setDnsPerMinute(int perMinute) {
        this.jobDnsLimiter = new TokenBucketRateLimiter(Math.max(1, perMinute), Duration.ofMinutes(1));
    }

    /** Test support: refills the job's own DNS bucket. */
    void resetLimits() {
        jobDnsLimiter.reset();
    }

    /** One bounded round: claims and checks up to {@code batch-size} due domains. Never throws for one bad row. */
    public RoundResult runRound() {
        int claimed = 0;
        int ok = 0;
        int miss = 0;
        int err = 0;
        int down = 0;
        boolean budgetStop = false;
        for (int i = 0; i < Math.max(1, props.getBatchSize()); i++) {
            Claim c = claim();
            if (c == null) {
                break;
            }
            String ruleFailure = service.revalidate(c.domain()); // local rule, no DNS involved
            if (ruleFailure == null && !(jobDnsLimiter.tryAcquire("job") == 0 && service.tryAcquireGlobalDnsToken())) {
                release(c); // no DNS budget left: put the row back untouched and end the round
                budgetStop = true;
                break;
            }
            claimed++;
            try {
                switch (apply(c, ruleFailure, ruleFailure != null ? Dns.MISSING : lookup(c))) {
                    case "OK" -> ok++;
                    case "MISS" -> miss++;
                    case "ERROR" -> err++;
                    case "DOWNGRADED" -> down++;
                    default -> { }
                }
            } catch (RuntimeException e) {
                // e.g. the row lock stayed busy: the lease expires and the row is retried; no exception text (SEC-01d)
                log.warn("Custom domain re-verification of tenant {} failed ({}); retried after the lease", c.slug(),
                        e.getClass().getSimpleName());
            }
        }
        if (claimed > 0 || budgetStop) {
            log.info("Custom domain re-verification round: claimed={} ok={} miss={} dnsError={} downgraded={} budgetStop={}",
                    claimed, ok, miss, err, down, budgetStop);
        }
        return new RoundResult(claimed, ok, miss, err, down, budgetStop);
    }

    /** Claims the most overdue VERIFIED domain (skipping rows another instance holds); null when none is due. */
    private Claim claim() {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        // micro precision: what the database stores, so applyLocked can compare it with the row's due time
        Timestamp leaseEnd = Timestamp.from(now.plus(lease()).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        List<Claim> rows = jdbc.query("""
                WITH c AS (SELECT id, custom_domain_reverify_due_at AS old_due FROM master.tenants
                           WHERE custom_domain_status = 'VERIFIED'
                             AND (custom_domain_reverify_due_at IS NULL OR custom_domain_reverify_due_at <= ?)
                           ORDER BY custom_domain_reverify_due_at NULLS FIRST, id
                           LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE master.tenants t SET custom_domain_reverify_due_at = ?
                FROM c WHERE t.id = c.id
                RETURNING t.id, t.slug, t.custom_domain, t.custom_domain_token, c.old_due
                """, (rs, n) -> new Claim(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5), leaseEnd), nowTs, leaseEnd);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Gives a claimed row back with its previous due time (only if nobody rescheduled it since). */
    private void release(Claim c) {
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE id = ? AND custom_domain_status = 'VERIFIED' "
                + "AND custom_domain_reverify_due_at = ?", c.previousDue(), c.id(), c.leaseEnd());
    }

    private Duration lease() {
        Duration l = props.getLease();
        return l == null || l.compareTo(Duration.ofSeconds(60)) < 0 ? Duration.ofSeconds(60) : l;
    }

    private Dns lookup(Claim c) {
        String expected = domainProps.getTxtValuePrefix() + c.token();
        try {
            boolean found = dns.lookupTxt(service.txtName(c.domain())).stream()
                    .anyMatch(v -> v != null && v.strip().equals(expected));
            return found ? Dns.FOUND : Dns.MISSING;
        } catch (DnsTxtResolver.DnsLookupException e) {
            return Dns.UNKNOWN; // no exception text in the log
        }
    }

    private String apply(Claim c, String ruleFailure, Dns answer) {
        boolean downgraded = false;
        try {
            String result = masterTx.execute(status -> lockTimeout.run(CustomDomainService.BUSY,
                    "The custom domain is being changed right now.", () -> applyLocked(c, ruleFailure, answer)));
            downgraded = "DOWNGRADED".equals(result);
            return result;
        } finally {
            if (downgraded) {
                originPolicy.invalidateAll(); // after the transaction ended (commit or rollback), never before
            }
        }
    }

    private String applyLocked(Claim c, String ruleFailure, Dns answer) {
        Tenant t = tenantRepository.lockById(c.id()).orElse(null);
        if (t == null || t.getCustomDomainStatus() != CustomDomainStatus.VERIFIED
                || !Objects.equals(c.domain(), t.getCustomDomain())
                || !Objects.equals(c.token(), t.getCustomDomainToken())
                || !c.leaseEnd().toInstant().equals(t.getCustomDomainReverifyDueAt())) {
            // changed while DNS was queried (claim replaced/removed/failed, or the lease ended and another round
            // re-claimed or rescheduled the row): that answer is not about the current claim, so it is dropped and
            // can never be counted twice
            return "STALE";
        }
        Instant now = clock.instant();
        switch (answer) {
            case FOUND -> {
                t.setCustomDomainCheckedAt(now);
                t.setCustomDomainVerifiedAt(now);
                t.setCustomDomainLastVerifiedAt(now);
                t.setCustomDomainFailure(null);
                t.setCustomDomainMissCount(0);
                t.setCustomDomainMissingSince(null);
                t.setCustomDomainReverifyDueAt(schedule.afterOk(now));
                tenantRepository.saveAndFlush(t);
                return "OK";
            }
            case UNKNOWN -> {
                // no definitive answer: nothing about trust changes, the streak is neither extended nor reset
                t.setCustomDomainReverifyDueAt(schedule.afterDnsError(now));
                tenantRepository.saveAndFlush(t);
                return "ERROR";
            }
            default -> {
                int misses = t.getCustomDomainMissCount() + 1;
                Instant since = t.getCustomDomainMissingSince() == null ? now : t.getCustomDomainMissingSince();
                boolean graceOver = !since.plus(props.getGrace()).isAfter(now);
                if (ruleFailure != null || (misses >= Math.max(1, props.getMissesRequired()) && graceOver)) {
                    String reason = ruleFailure != null ? ruleFailure : CustomDomainService.TXT_NOT_FOUND;
                    t.setCustomDomainStatus(CustomDomainStatus.FAILED);
                    t.setCustomDomainCheckedAt(now);
                    t.setCustomDomainVerifiedAt(null);
                    t.setCustomDomainLastVerifiedAt(now); // F1b: VERIFIED -> FAILED stamps now
                    t.setCustomDomainFailure(reason);
                    t.clearReverifyState();
                    tenantRepository.saveAndFlush(t);
                    auditRepository.save(TenantDomainAuditLog.builder()
                            .actorType(CustomDomainService.ActorType.SYSTEM.name()).tenantId(t.getId())
                            .tenantSlug(t.getSlug()).action(ACTION).domain(c.domain())
                            .previousStatus(CustomDomainStatus.VERIFIED.name())
                            .newStatus(CustomDomainStatus.FAILED.name()).detail(reason).build());
                    log.warn("Custom domain of tenant {} downgraded VERIFIED -> FAILED by re-verification ({})",
                            t.getSlug(), reason);
                    return "DOWNGRADED";
                }
                t.setCustomDomainCheckedAt(now);
                t.setCustomDomainMissCount(misses);
                t.setCustomDomainMissingSince(since);
                t.setCustomDomainReverifyDueAt(schedule.afterMiss(now));
                tenantRepository.saveAndFlush(t);
                log.info("Custom domain of tenant {}: TXT proof missing ({} in a row since {}), still trusted",
                        t.getSlug(), misses, since);
                return "MISS";
            }
        }
    }
}

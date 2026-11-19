package com.example.perfume_budget.platform.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.support.FakeDnsTxtResolver;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantHostLookup;
import com.example.perfume_budget.tenantauth.AbstractTenantWebTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOMAIN-03 on real PostgreSQL with a fake {@link FakeDnsTxtResolver} and a fake clock: the scheduled re-verification
 * of VERIFIED custom domains (grace + N consecutive misses, transient DNS errors never downgrade, audit, F1b stamp,
 * cache invalidation, row claim / lease / SKIP LOCKED, DNS budgets, jitter, tenant isolation, no token in logs).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example",
        "cors.tenant-cache.ttl-seconds=30",
        "cors.tenant-cache.negative-ttl-seconds=30",
        "cors.tenant-cache.max-lookups-per-second=1000",
        "cors.tenant-cache.max-lookups-per-ip-per-second=1000",
        "tenant.custom-domain.verify-global-per-minute=5",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0"
})
class CustomDomainReverifyIntegrationTest extends AbstractTenantWebTest {

    static final String ACME_HOST = "shop.acme-brand.com";
    static final String BRAVO_HOST = "shop.bravo-brand.com";
    static final String ACME_TOKEN = "tok-acme-SECRET-1";
    static final String BRAVO_TOKEN = "tok-bravo-SECRET-2";
    static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

    @Autowired CustomDomainReverifier job;
    @Autowired CustomDomainReverifyScheduler scheduler;
    @Autowired CustomDomainReverifyProperties props;
    @Autowired ReverifySchedule schedule;
    @Autowired CustomDomainService service;
    @Autowired FakeDnsTxtResolver dns;
    @Autowired TenantOriginPolicy originPolicy;
    @Autowired TenantHostLookup hostLookup;
    @Autowired javax.sql.DataSource dataSource;

    MutableClock clock;
    long auditMark;
    ListAppender<ILoggingEvent> logs;

    static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUpReverify() {
        clock = new MutableClock(T0);
        job.setClock(clock);
        job.setDnsPerMinute(props.getDnsPerMinute());
        job.resetLimits();
        service.resetLimits();
        schedule.setRandom(() -> 0.0); // no jitter unless a test asks
        props.setEnabled(false);
        props.setBatchSize(10);
        props.setMissesRequired(3);
        props.setGrace(Duration.ofHours(24));
        props.setInterval(Duration.ofHours(24));
        props.setMissRetryInterval(Duration.ofHours(6));
        props.setErrorRetryInterval(Duration.ofMinutes(30));
        props.setLease(Duration.ofMinutes(10));
        props.setJitterPercent(20);
        dns.reset();
        auditMark = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM master.tenant_domain_audit_log", Long.class);
        verified("acme", ACME_HOST, ACME_TOKEN);
        verified("bravo", BRAVO_HOST, BRAVO_TOKEN);
        publish(ACME_HOST, ACME_TOKEN);
        publish(BRAVO_HOST, BRAVO_TOKEN);
        originPolicy.invalidateAll();
        logs = new ListAppender<>();
        logs.start();
        Logger l = (Logger) LoggerFactory.getLogger("com.example.perfume_budget");
        l.setLevel(Level.DEBUG);
        l.addAppender(logs);
    }

    @AfterEach
    void tearDownReverify() {
        ((Logger) LoggerFactory.getLogger("com.example.perfume_budget")).detachAppender(logs);
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, custom_domain_status = NULL, custom_domain_token = NULL, "
                + "custom_domain_requested_at = NULL, custom_domain_checked_at = NULL, custom_domain_verified_at = NULL, "
                + "custom_domain_last_verified_at = NULL, custom_domain_failure = NULL, custom_domain_reverify_due_at = NULL, "
                + "custom_domain_miss_count = 0, custom_domain_missing_since = NULL WHERE slug IN ('acme', 'bravo')");
        dns.reset();
        originPolicy.invalidateAll();
        TenantContext.clear();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void verified(String slug, String domain, String token) {
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, custom_domain_status = NULL, custom_domain_token = NULL, "
                + "custom_domain_verified_at = NULL, custom_domain_last_verified_at = NULL, custom_domain_failure = NULL, "
                + "custom_domain_reverify_due_at = NULL, custom_domain_miss_count = 0, custom_domain_missing_since = NULL "
                + "WHERE slug = ?", slug);
        jdbc.update("UPDATE master.tenants SET custom_domain = ?, custom_domain_status = 'VERIFIED', custom_domain_token = ?, "
                + "custom_domain_verified_at = ? WHERE slug = ?", domain, token, Timestamp.from(T0.minus(Duration.ofDays(1))), slug);
    }

    private void publish(String domain, String token) {
        dns.put("_shopfi-verification." + domain, "shopfi-verification=" + token);
    }

    private void unpublish(String domain) {
        dns.remove("_shopfi-verification." + domain);
    }

    private Map<String, Object> row(String slug) {
        return jdbc.queryForMap("SELECT * FROM master.tenants WHERE slug = ?", slug);
    }

    private String status(String slug) {
        return (String) row(slug).get("custom_domain_status");
    }

    private Instant instant(String slug, String column) {
        Timestamp t = jdbc.queryForObject("SELECT " + column + " FROM master.tenants WHERE slug = ?", Timestamp.class, slug);
        return t == null ? null : t.toInstant();
    }

    private int misses(String slug) {
        return ((Number) row(slug).get("custom_domain_miss_count")).intValue();
    }

    private long reverifyAudits(String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM master.tenant_domain_audit_log WHERE tenant_slug = ? AND action = 'REVERIFY' AND id > ?",
                Long.class, slug, auditMark);
    }

    private long audits(String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM master.tenant_domain_audit_log WHERE tenant_slug = ? AND id > ?", Long.class, slug, auditMark);
    }

    /** Runs rounds, advancing the clock, until the given tenant is no longer due (everything due has been processed). */
    private CustomDomainReverifier.RoundResult round() {
        job.resetLimits(); // the DNS budgets are real-time; tests that play days of fake time must not hit them
        service.resetLimits();
        return job.runRound();
    }

    private void makeDue(String slug) {
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE slug = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), slug);
    }

    // ---- downgrade policy -------------------------------------------------------------------------------------------

    @Test
    void missingRecordDowngradesOnlyAfterNConsecutiveMissesSpanningTheGrace() {
        assertThat(hostLookup.findByHost(ACME_HOST)).isPresent();
        assertThat(originPolicy.isAllowed("https://" + ACME_HOST, "ip")).isTrue(); // primes the CORS cache
        unpublish(ACME_HOST);

        // miss 1 (t0): counted, still trusted
        assertThat(round().misses()).isEqualTo(1);
        assertThat(status("acme")).isEqualTo("VERIFIED");
        assertThat(misses("acme")).isEqualTo(1);
        assertThat(instant("acme", "custom_domain_missing_since")).isEqualTo(T0);
        assertThat(instant("acme", "custom_domain_reverify_due_at")).isEqualTo(T0.plus(Duration.ofHours(6)));

        // not due yet: nothing is looked up
        int before = dns.lookups();
        assertThat(round().claimed()).isZero();
        assertThat(dns.lookups()).isEqualTo(before);

        clock.advance(Duration.ofHours(6));
        assertThat(round().misses()).isEqualTo(1); // miss 2 (t0+6h)
        clock.advance(Duration.ofHours(6));
        assertThat(round().misses()).isEqualTo(1); // miss 3 (t0+12h): N reached but grace (24h) not over
        assertThat(status("acme")).isEqualTo("VERIFIED");
        assertThat(misses("acme")).isEqualTo(3);
        assertThat(reverifyAudits("acme")).isZero();
        assertThat(hostLookup.findByHost(ACME_HOST)).isPresent();

        clock.advance(Duration.ofHours(6));
        assertThat(round().misses()).isEqualTo(1); // miss 4 (t0+18h): still inside the grace
        assertThat(status("acme")).isEqualTo("VERIFIED");

        clock.advance(Duration.ofHours(6)); // t0+24h: first miss + grace reached
        CustomDomainReverifier.RoundResult r = round();
        assertThat(r.downgraded()).isEqualTo(1);
        assertThat(status("acme")).isEqualTo("FAILED");
        Map<String, Object> acme = row("acme");
        assertThat(acme.get("custom_domain_failure")).isEqualTo("TXT_RECORD_NOT_FOUND");
        assertThat(acme.get("custom_domain_verified_at")).isNull();
        // F1b: VERIFIED -> FAILED stamps last_verified_at with the downgrade time (not the old verification time)
        assertThat(instant("acme", "custom_domain_last_verified_at")).isEqualTo(clock.instant());
        assertThat(instant("acme", "custom_domain_checked_at")).isEqualTo(clock.instant());
        assertThat(acme.get("custom_domain_reverify_due_at")).isNull();
        assertThat(misses("acme")).isZero();
        assertThat(acme.get("custom_domain_missing_since")).isNull();
        // never trusted again: resolution and CORS (cache primed above) flip at once
        assertThat(hostLookup.findByHost(ACME_HOST)).isEmpty();
        assertThat(originPolicy.isAllowed("https://" + ACME_HOST, "ip")).isFalse();
        // the other tenant is untouched
        assertThat(status("bravo")).isEqualTo("VERIFIED");
        assertThat(reverifyAudits("bravo")).isZero();
    }

    @Test
    void auditRowIsWrittenForTheTransitionAndCarriesNoToken() {
        unpublish(ACME_HOST);
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        assertThat(round().downgraded()).isEqualTo(1);
        Map<String, Object> a = jdbc.queryForMap("SELECT * FROM master.tenant_domain_audit_log WHERE tenant_slug = 'acme' "
                + "AND action = 'REVERIFY' AND id > " + auditMark);
        assertThat(a.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(a.get("actor_id")).isNull();
        assertThat(a.get("actor_email")).isNull();
        assertThat(a.get("domain")).isEqualTo(ACME_HOST);
        assertThat(a.get("previous_status")).isEqualTo("VERIFIED");
        assertThat(a.get("new_status")).isEqualTo("FAILED");
        assertThat(a.get("detail")).isEqualTo("TXT_RECORD_NOT_FOUND");
        assertThat(a.toString()).doesNotContain(ACME_TOKEN);
        assertThat(audits("acme")).isEqualTo(1); // exactly one row for the one transition
        // the platform history endpoint's source lists it
        assertThat(service.platformView("acme").history()).extracting(CustomDomainAuditRow::action).contains("REVERIFY");
    }

    @Test
    void graceOverButTooFewMissesDoesNotDowngrade() {
        props.setMissesRequired(3);
        props.setGrace(Duration.ofMinutes(10));
        props.setMissRetryInterval(Duration.ofHours(1));
        unpublish(ACME_HOST);
        round(); // miss 1
        clock.advance(Duration.ofHours(1));
        round(); // miss 2: grace long over, only 2 misses
        assertThat(status("acme")).isEqualTo("VERIFIED");
        assertThat(misses("acme")).isEqualTo(2);
        clock.advance(Duration.ofHours(1));
        assertThat(round().downgraded()).isEqualTo(1); // miss 3
        assertThat(status("acme")).isEqualTo("FAILED");
    }

    @Test
    void aWrongTokenIsADefiniteMissToo() {
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        dns.put("_shopfi-verification." + ACME_HOST, "shopfi-verification=somebody-elses-token", "v=spf1 -all");
        assertThat(round().downgraded()).isEqualTo(1);
        assertThat(status("acme")).isEqualTo("FAILED");
    }

    @Test
    void transientDnsErrorsNeverDowngradeNoMatterHowLong() {
        dns.fail("_shopfi-verification." + ACME_HOST);
        for (int i = 0; i < 12; i++) { // 12 x 3 days of outage
            CustomDomainReverifier.RoundResult r = round();
            assertThat(r.dnsErrors()).isEqualTo(1);
            assertThat(r.downgraded()).isZero();
            clock.advance(Duration.ofDays(3));
        }
        assertThat(status("acme")).isEqualTo("VERIFIED");
        assertThat(misses("acme")).isZero();
        assertThat(row("acme").get("custom_domain_missing_since")).isNull();
        assertThat(reverifyAudits("acme")).isZero();
        assertThat(hostLookup.findByHost(ACME_HOST)).isPresent();
    }

    @Test
    void dnsErrorSchedulesARetryAndLeavesTheMissStreakAlone() {
        unpublish(ACME_HOST);
        round(); // miss 1
        dns.fail("_shopfi-verification." + ACME_HOST);
        clock.advance(Duration.ofHours(6));
        assertThat(round().dnsErrors()).isEqualTo(1);
        assertThat(misses("acme")).isEqualTo(1); // neither extended nor reset
        assertThat(instant("acme", "custom_domain_missing_since")).isEqualTo(T0);
        assertThat(instant("acme", "custom_domain_reverify_due_at")).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(status("acme")).isEqualTo("VERIFIED");
    }

    @Test
    void aFoundRecordResetsTheStreakAndRestampsVerification() {
        unpublish(ACME_HOST);
        round();
        clock.advance(Duration.ofHours(6));
        round();
        assertThat(misses("acme")).isEqualTo(2);
        publish(ACME_HOST, ACME_TOKEN);
        clock.advance(Duration.ofHours(6));
        CustomDomainReverifier.RoundResult r = round();
        assertThat(r.stillVerified()).isEqualTo(1);
        assertThat(misses("acme")).isZero();
        assertThat(row("acme").get("custom_domain_missing_since")).isNull();
        assertThat(instant("acme", "custom_domain_last_verified_at")).isEqualTo(clock.instant());
        assertThat(instant("acme", "custom_domain_verified_at")).isEqualTo(clock.instant());
        assertThat(instant("acme", "custom_domain_reverify_due_at")).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(reverifyAudits("acme")).isZero(); // no transition, no audit row
    }

    @Test
    void aStoredDomainThatBreaksTheRulesIsDowngradedAtOnceWithItsCode() {
        verified("acme", "acme.shopfi.test", ACME_TOKEN); // under the platform base domain (legacy/direct edit)
        CustomDomainReverifier.RoundResult r = round();
        assertThat(r.downgraded()).isEqualTo(1);
        assertThat(row("acme").get("custom_domain_failure")).isEqualTo("DOMAIN_PLATFORM");
        assertThat(status("acme")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT detail FROM master.tenant_domain_audit_log WHERE tenant_slug = 'acme' "
                + "AND action = 'REVERIFY' AND id > " + auditMark, String.class)).isEqualTo("DOMAIN_PLATFORM");
    }

    @Test
    void failedDomainIsNotCheckedAgainAndPendingNeverClaimed() {
        jdbc.update("UPDATE master.tenants SET custom_domain_status = 'FAILED' WHERE slug = 'acme'");
        jdbc.update("UPDATE master.tenants SET custom_domain_status = 'PENDING' WHERE slug = 'bravo'");
        assertThat(round().claimed()).isZero();
        assertThat(dns.lookups()).isZero();
    }

    // ---- cache invalidation -----------------------------------------------------------------------------------------

    @Test
    void downgradeInvalidatesTheOriginCachesAfterTheTransaction() {
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        assertThat(originPolicy.isAllowed("https://" + ACME_HOST, "ip")).isTrue();
        unpublish(ACME_HOST);
        assertThat(round().downgraded()).isEqualTo(1);
        // production TTL is 30 s: only invalidateAll() can explain the immediate flip
        assertThat(originPolicy.isAllowed("https://" + ACME_HOST, "ip")).isFalse();
        assertThat(originPolicy.isAllowed("https://" + BRAVO_HOST, "ip")).isTrue();
    }

    // ---- claim, lease, concurrency ----------------------------------------------------------------------------------

    @Test
    void claimedRowIsLeasedSoNobodyElseChecksItAndComesBackAfterTheLease() {
        props.setBatchSize(1);
        assertThat(round().claimed()).isEqualTo(1);
        assertThat(round().claimed()).isEqualTo(1); // the other tenant
        assertThat(round().claimed()).isZero(); // both have been checked and rescheduled 24 h out
        clock.advance(Duration.ofHours(24));
        // a "crashed" instance: row claimed (lease pushed 10 min) but never applied
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE slug = 'bravo'",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))));
        assertThat(round().claimed()).isEqualTo(1); // acme only
        assertThat(round().claimed()).isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(round().claimed()).isEqualTo(1); // lease over: bravo is back
    }

    @Test
    void batchSizeBoundsARound() {
        props.setBatchSize(1);
        assertThat(round().claimed()).isEqualTo(1);
        assertThat(dns.lookups()).isEqualTo(1);
        props.setBatchSize(5);
        assertThat(round().claimed()).isEqualTo(1); // the remaining due row only
        assertThat(dns.lookups()).isEqualTo(2);
    }

    @Test
    void mostOverdueRowIsClaimedFirst() {
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE slug = 'acme'",
                Timestamp.from(T0.minus(Duration.ofHours(1))));
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE slug = 'bravo'",
                Timestamp.from(T0.minus(Duration.ofHours(5))));
        props.setBatchSize(1);
        unpublish(BRAVO_HOST);
        assertThat(round().misses()).isEqualTo(1);
        assertThat(misses("bravo")).isEqualTo(1);
        assertThat(misses("acme")).isZero();
        assertThat(dns.lookups()).isEqualTo(1);
    }

    @Test
    void rowLockedByAnotherInstanceIsSkippedNotWaitedFor() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT id FROM master.tenants WHERE slug = 'acme' FOR UPDATE");
                long start = System.nanoTime();
                job.resetLimits();
                service.resetLimits();
                ExecutorService one = Executors.newSingleThreadExecutor();
                CustomDomainReverifier.RoundResult r;
                try {
                    // bounded: a claim that WAITS for the locked row would otherwise hang this test
                    r = one.submit(job::runRound).get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("the claim waited for a row locked by another instance");
                } finally {
                    c.rollback(); // releases the row so a blocked claim can end
                    one.shutdownNow();
                }
                assertThat(r.claimed()).isEqualTo(1); // bravo
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
                assertThat(dns.lookups()).isEqualTo(1);
                assertThat(instant("bravo", "custom_domain_reverify_due_at")).isEqualTo(T0.plus(Duration.ofHours(24)));
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void twoInstancesRacingNeverCheckTheSameRowTwice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                makeDue("acme");
                makeDue("bravo");
                dns.reset();
                publish(ACME_HOST, ACME_TOKEN);
                publish(BRAVO_HOST, BRAVO_TOKEN);
                job.resetLimits();
                service.resetLimits();
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<CustomDomainReverifier.RoundResult>> fs = List.of(
                        pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return job.runRound(); }),
                        pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return job.runRound(); }));
                int claimed = 0;
                for (Future<CustomDomainReverifier.RoundResult> f : fs) {
                    claimed += f.get(30, TimeUnit.SECONDS).claimed();
                }
                assertThat(claimed).isEqualTo(2);
                assertThat(dns.lookups()).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- budgets ----------------------------------------------------------------------------------------------------

    @Test
    void roundStopsWhenTheGlobalDnsBudgetIsUsedUpAndLooksNothingUp() {
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryAcquireGlobalDnsToken()).isTrue(); // the test profile's 5 per minute
        }
        CustomDomainReverifier.RoundResult r = job.runRound();
        assertThat(r.budgetStop()).isTrue();
        assertThat(r.claimed()).isZero();
        assertThat(dns.lookups()).isZero();
        assertThat(status("acme")).isEqualTo("VERIFIED"); // rows stay due, nothing claimed or changed
        assertThat(row("acme").get("custom_domain_reverify_due_at")).isNull();
    }

    @Test
    void jobSpendsTokensFromTheSharedGlobalBudget() {
        // 5 per minute globally: 2 domains checked leave 3 for manual verifies
        job.runRound();
        int left = 0;
        while (service.tryAcquireGlobalDnsToken()) {
            left++;
        }
        assertThat(left).isEqualTo(3);
    }

    // ---- jitter / schedule ------------------------------------------------------------------------------------------

    @Test
    void jitterSpreadsTheNextCheckBetweenBaseAndBasePlusPercent() {
        Instant now = T0;
        schedule.setRandom(() -> 0.0);
        assertThat(schedule.afterOk(now)).isEqualTo(now.plus(Duration.ofHours(24)));
        schedule.setRandom(() -> 0.5);
        assertThat(schedule.afterOk(now)).isEqualTo(now.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(144))); // +10%
        schedule.setRandom(() -> 0.999999999);
        assertThat(schedule.afterOk(now)).isBefore(now.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(288)));
        assertThat(schedule.afterOk(now)).isAfter(now.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(287)));
        props.setJitterPercent(0);
        assertThat(schedule.afterMiss(now)).isEqualTo(now.plus(Duration.ofHours(6)));
        assertThat(schedule.afterDnsError(now)).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void jobUsesTheJitteredScheduleForDifferentRows() {
        double[] values = {0.0, 0.5};
        int[] i = {0};
        schedule.setRandom(() -> values[i[0]++ % 2]);
        round();
        Instant a = instant("acme", "custom_domain_reverify_due_at");
        Instant b = instant("bravo", "custom_domain_reverify_due_at");
        assertThat(a).isNotEqualTo(b);
        assertThat(Duration.between(a, b).abs()).isEqualTo(Duration.ofMinutes(144));
    }

    // ---- manual verify interplay + schema invariant ------------------------------------------------------------------

    @Test
    void manualVerifyReschedulesAndClearsTheStreakAndFailureClearsState() {
        unpublish(ACME_HOST);
        round(); // miss 1
        assertThat(misses("acme")).isEqualTo(1);
        publish(ACME_HOST, ACME_TOKEN);
        service.verify("acme", new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, 1L, "p@x.test"));
        assertThat(status("acme")).isEqualTo("VERIFIED");
        assertThat(misses("acme")).isZero();
        assertThat(row("acme").get("custom_domain_missing_since")).isNull();
        assertThat(row("acme").get("custom_domain_reverify_due_at")).isNotNull();
        unpublish(ACME_HOST);
        service.verify("acme", new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, 1L, "p@x.test"));
        assertThat(status("acme")).isEqualTo("FAILED");
        assertThat(row("acme").get("custom_domain_reverify_due_at")).isNull();
        assertThat(misses("acme")).isZero();
    }

    @Test
    void newClaimAndRemovalClearTheState() {
        unpublish(ACME_HOST);
        round();
        assertThat(misses("acme")).isEqualTo(1);
        service.request("acme", "other.acme-brand.com", new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, 1L, null));
        assertThat(status("acme")).isEqualTo("PENDING");
        assertThat(misses("acme")).isZero();
        assertThat(row("acme").get("custom_domain_reverify_due_at")).isNull();
        assertThat(row("acme").get("custom_domain_missing_since")).isNull();
    }

    @Test
    void databaseRejectsRescheduleStateOnANonVerifiedRow() {
        jdbc.update("UPDATE master.tenants SET custom_domain_status = 'FAILED' WHERE slug = 'acme'");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE master.tenants SET custom_domain_miss_count = 1 WHERE slug = 'acme'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE master.tenants SET custom_domain_reverify_due_at = now() WHERE slug = 'acme'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditActionReverifyIsAcceptedAndUnknownActionsStillRejected() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO master.tenant_domain_audit_log (actor_type, tenant_id, tenant_slug, "
                + "action, domain) VALUES ('SYSTEM', 1, 'acme', 'BOGUS', 'x.example.com')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- scheduling switch, isolation, logging ----------------------------------------------------------------------

    @Test
    void disabledByDefaultAndSchedulerDoesNotStart() {
        assertThat(props.isEnabled()).isFalse();
        assertThat(scheduler.isRunning()).isFalse();
        scheduler.start();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void enabledSchedulerStartsOnItsOwnThreadAndStops() {
        props.setEnabled(true);
        props.setInitialDelay(Duration.ofHours(1)); // never fires during the test
        try {
            scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();
        } finally {
            scheduler.stop();
            props.setEnabled(false);
            props.setInitialDelay(Duration.ofMinutes(2));
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void roundRunsWithoutTenantContextAndTouchesOnlyTheCheckedTenantsRows() {
        TenantContext.clear();
        unpublish(ACME_HOST);
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        long bravoAuditsBefore = audits("bravo");
        round();
        assertThat(TenantContext.getSlug()).isNull();
        assertThat(status("acme")).isEqualTo("FAILED");
        assertThat(status("bravo")).isEqualTo("VERIFIED");
        assertThat(audits("bravo")).isEqualTo(bravoAuditsBefore);
        assertThat(jdbc.queryForList("SELECT DISTINCT tenant_slug FROM master.tenant_domain_audit_log WHERE action = 'REVERIFY' AND id > " + auditMark,
                String.class)).containsExactly("acme");
    }

    @Test
    void neverLogsATokenOrTheTxtValueOrDnsErrorText() {
        unpublish(ACME_HOST);
        dns.fail("_shopfi-verification." + BRAVO_HOST);
        round(); // miss path (misses-required 3): logs "TXT proof missing ... still trusted"; bravo: DNS error
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).anyMatch(m -> m.contains("still trusted"));
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        clock.advance(Duration.ofHours(30));
        round(); // downgrade path
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).anyMatch(m -> m.contains("downgraded"));
        for (ILoggingEvent e : logs.list) {
            String text = e.getFormattedMessage() + " " + e.getThrowableProxy();
            assertThat(text).doesNotContain(ACME_TOKEN).doesNotContain(BRAVO_TOKEN)
                    .doesNotContain("shopfi-verification=").doesNotContain("fake timeout");
        }
    }

    // ---- rework: stale-result guard, lease, job bucket, schema --------------------------------------------------------

    private static final String[] STATE_COLUMNS = {"custom_domain", "custom_domain_status", "custom_domain_token",
            "custom_domain_checked_at", "custom_domain_verified_at", "custom_domain_last_verified_at",
            "custom_domain_failure", "custom_domain_miss_count", "custom_domain_missing_since"};

    private Map<String, Object> state(String slug) {
        Map<String, Object> row = row(slug);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (String c : STATE_COLUMNS) {
            m.put(c, row.get(c));
        }
        return m;
    }

    /** bravo is not due, so the one-shot hook always runs inside acme's lookup. */
    private void onlyAcmeDue() {
        jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = ? WHERE slug = 'bravo'",
                Timestamp.from(T0.plus(Duration.ofDays(30))));
    }

    private static CustomDomainService.Actor platform() {
        return new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, 1L, "p@x.test");
    }

    /** Job answer is a definite miss and would downgrade at once if it were applied: any effect is visible. */
    private void armForImmediateDowngrade() {
        props.setMissesRequired(1);
        props.setGrace(Duration.ZERO);
        onlyAcmeDue();
    }

    private void assertDroppedAndUntouched(Map<String, Object> afterHook, CustomDomainReverifier.RoundResult r) {
        assertThat(r.claimed()).isEqualTo(1);
        assertThat(r.downgraded()).isZero();
        assertThat(r.misses()).isZero();
        assertThat(r.stillVerified()).isZero();
        assertThat(state("acme")).isEqualTo(afterHook);
        assertThat(reverifyAudits("acme")).isZero();
    }

    @Test
    void staleResultIsDroppedWhenTheDomainWasRemoved() {
        armForImmediateDowngrade();
        unpublish(ACME_HOST);
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            service.remove("acme", platform());
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertDroppedAndUntouched(snap[0], r);
        assertThat(row("acme").get("custom_domain")).isNull();
    }

    @Test
    void staleResultIsDroppedWhenTheSameDomainWasReclaimedWithANewToken() {
        armForImmediateDowngrade();
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            service.remove("acme", platform());
            service.request("acme", ACME_HOST, platform()); // same domain, NEW token
            String newToken = (String) row("acme").get("custom_domain_token");
            unpublish(ACME_HOST); // the old token's record is gone; the new one is published
            dns.put("_shopfi-verification." + ACME_HOST, "shopfi-verification=" + newToken);
            service.verify("acme", platform()); // VERIFIED again: same domain, same status, different token
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(snap[0].get("custom_domain_status")).isEqualTo("VERIFIED");
        assertThat(snap[0].get("custom_domain_token")).isNotEqualTo(ACME_TOKEN);
        assertDroppedAndUntouched(snap[0], r);
    }

    @Test
    void staleResultIsDroppedWhenTheDomainWasReplacedByAnotherClaim() {
        armForImmediateDowngrade();
        unpublish(ACME_HOST);
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            service.request("acme", "other.acme-brand.com", platform());
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(snap[0].get("custom_domain_status")).isEqualTo("PENDING");
        assertDroppedAndUntouched(snap[0], r);
    }

    @Test
    void staleResultIsDroppedWhenAVerifiedRowChangedDomainButKeptItsToken() {
        armForImmediateDowngrade(); // isolates the domain comparison: status and token are unchanged
        unpublish(ACME_HOST);
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            jdbc.update("UPDATE master.tenants SET custom_domain = 'moved.acme-brand.com' WHERE slug = 'acme'");
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(snap[0].get("custom_domain_status")).isEqualTo("VERIFIED");
        assertDroppedAndUntouched(snap[0], r);
    }

    @Test
    void staleResultIsDroppedWhenAVerifiedRowChangedTokenButKeptDomainAndLease() {
        armForImmediateDowngrade(); // isolates the token comparison: domain, status and due time are unchanged
        unpublish(ACME_HOST);
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            jdbc.update("UPDATE master.tenants SET custom_domain_token = 'rotated-by-direct-edit' WHERE slug = 'acme'");
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(snap[0].get("custom_domain_status")).isEqualTo("VERIFIED");
        assertDroppedAndUntouched(snap[0], r);
    }

    @Test
    void staleResultIsDroppedWhenAManualVerifyChangedTheStatusDuringTheLookup() {
        armForImmediateDowngrade();
        Map<String, Object>[] snap = new Map[1];
        dns.duringNextLookup(f -> {
            unpublish(ACME_HOST);
            service.verify("acme", platform()); // record gone: VERIFIED -> FAILED, same domain and token
            snap[0] = state("acme");
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(snap[0].get("custom_domain_status")).isEqualTo("FAILED");
        assertDroppedAndUntouched(snap[0], r);
    }

    @Test
    void claimIsLeasedExactlyForTheLeaseAndANestedRoundClaimsNothing() {
        onlyAcmeDue();
        Instant[] due = new Instant[1];
        CustomDomainReverifier.RoundResult[] nested = new CustomDomainReverifier.RoundResult[1];
        dns.duringNextLookup(f -> {
            due[0] = instant("acme", "custom_domain_reverify_due_at");
            nested[0] = job.runRound();
        });
        CustomDomainReverifier.RoundResult r = round();
        assertThat(due[0]).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(nested[0].claimed()).isZero();
        assertThat(r.claimed()).isEqualTo(1);
        assertThat(r.stillVerified()).isEqualTo(1);
    }

    @Test
    void aLeaseThatEndedDuringASlowLookupCannotCountTheSameMissTwice() {
        onlyAcmeDue();
        unpublish(ACME_HOST);
        CustomDomainReverifier.RoundResult[] nested = new CustomDomainReverifier.RoundResult[1];
        dns.duringNextLookup(f -> {
            clock.advance(Duration.ofMinutes(11)); // the lease (10 min) ended: another round re-claims and checks the row
            nested[0] = job.runRound();
        });
        CustomDomainReverifier.RoundResult outer = round();
        assertThat(nested[0].claimed()).isEqualTo(1);
        assertThat(nested[0].misses()).isEqualTo(1);
        assertThat(outer.claimed()).isEqualTo(1);
        assertThat(outer.misses()).isZero(); // its answer is dropped
        assertThat(misses("acme")).isEqualTo(1); // counted once, not twice
        assertThat(instant("acme", "custom_domain_missing_since")).isEqualTo(T0.plus(Duration.ofMinutes(11)));
    }

    @Test
    void jobsOwnDnsBucketStopsLookupsWhileTheGlobalBucketStillHasTokens() {
        job.setDnsPerMinute(1); // global bucket: 5 per minute in this profile
        CustomDomainReverifier.RoundResult r = job.runRound();
        assertThat(r.claimed()).isEqualTo(1);
        assertThat(r.budgetStop()).isTrue();
        assertThat(dns.lookups()).isEqualTo(1);
        // the second row was handed back untouched and is still due
        long due = jdbc.queryForObject("SELECT count(*) FROM master.tenants WHERE slug IN ('acme','bravo') "
                + "AND custom_domain_reverify_due_at IS NULL", Long.class);
        assertThat(due).isEqualTo(1);
        int left = 0;
        while (service.tryAcquireGlobalDnsToken()) {
            left++;
        }
        assertThat(left).isEqualTo(4); // only the one lookup was charged to the global bucket
    }

    @Test
    void schemaRejectsJobStateOnARowWithNoStatusToo() {
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, custom_domain_status = NULL, custom_domain_token = NULL, "
                + "custom_domain_verified_at = NULL, custom_domain_reverify_due_at = NULL WHERE slug = 'acme'");
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain_reverify_due_at = now() WHERE slug = 'acme'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain_miss_count = 1 WHERE slug = 'acme'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain_missing_since = now() WHERE slug = 'acme'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

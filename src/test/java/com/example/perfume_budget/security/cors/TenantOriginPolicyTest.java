package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure unit tests of the origin decision (syntax, scheme/port rules, cache, rate limit); no Spring, no DB. */
class TenantOriginPolicyTest {

    final AtomicLong now = new AtomicLong(1_000_000_000L);
    TenantRegistryService registry;
    TenantResolutionProperties tenantProps;
    CorsProperties cors;

    @BeforeEach
    void setUp() {
        registry = Mockito.mock(TenantRegistryService.class);
        tenantProps = new TenantResolutionProperties();
        tenantProps.setBaseDomain("shopfi.test");
        tenantProps.setPlatformDomains(List.of("shopfi.test-apex.example", "api.shopfi.test"));
        cors = new CorsProperties();
        when(registry.findBySlug(anyString())).thenReturn(Optional.empty());
        when(registry.findByCustomDomain(anyString())).thenReturn(Optional.empty());
        active("acme");
    }

    private TenantOriginPolicy policy(boolean prod) {
        return new TenantOriginPolicy(registry, tenantProps, cors, prod, now::get);
    }

    private void active(String slug) {
        when(registry.findBySlug(slug)).thenReturn(Optional.of(tenant(slug, TenantStatus.ACTIVE, null)));
    }

    private static Tenant tenant(String slug, TenantStatus status, String domain) {
        return Tenant.builder().slug(slug).schemaName("tenant_" + slug).status(status).customDomain(domain).build();
    }

    @Test
    void activeSubdomainIsAllowedAndSuspendedIsNot() {
        when(registry.findBySlug("susp")).thenReturn(Optional.of(tenant("susp", TenantStatus.SUSPENDED, null)));
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        assertThat(p.isAllowed("https://susp.shopfi.test")).isFalse();
        assertThat(p.isAllowed("https://nobody.shopfi.test")).isFalse();
    }

    @Test
    void lookalikesAndMalformedOriginsAreRejectedWithoutTouchingTheRegistry() {
        TenantOriginPolicy p = policy(true);
        for (String o : List.of(
                "https://acme.shopfi.test@evil.com",
                "https://acme@acme.shopfi.test", "https://user:pw@acme.shopfi.test", "https://acme.shopfi.test:8443",
                "https://acme.shopfi.test:443", "http://acme.shopfi.test", "null", "", " ", "https://acme.shopfi.test/",
                "https://acme.shopfi.test/path", "https://acme.shopfi.test.", "https://a.b.shopfi.test",
                "https://.shopfi.test", "https://shopfi.test", "https://www.shopfi.test", "https://api.shopfi.test",
                "https://admin.shopfi.test", "https://platform.shopfi.test", "https://xn--acme-9ua.shopfi.test",
                "https://acmé.shopfi.test", "https://acme_x.shopfi.test", "https://-acme.shopfi.test",
                "https://acme-.shopfi.test", "https://acme..shopfi.test", "ftp://acme.shopfi.test",
                "https://acme.shopfi.test\r\nX: y", "https://acme.shopfi.test%2f", "*", "https://*.shopfi.test",
                "https://acme.shopfi.test, https://evil.com", "https://127.0.0.1", "https://[::1]")) {
            assertThat(p.isAllowed(o)).as(o).isFalse();
            Mockito.verifyNoMoreInteractions(registry);
        }
    }

    @Test
    void lookalikeDomainsAreDeniedAsCustomDomainCandidates() {
        TenantOriginPolicy p = policy(true);
        for (String o : List.of("https://acme.shopfi.test.evil.com", "https://evilshopfi.test")) {
            assertThat(p.isAllowed(o)).as(o).isFalse();
        }
        verify(registry, never()).findBySlug(anyString());
    }

    @Test
    void uppercaseHostIsNormalised() {
        assertThat(policy(true).isAllowed("HTTPS://ACME.SHOPFI.TEST")).isTrue();
    }

    @Test
    void nonProdAllowsHttpAndPorts_prodDoesNot() {
        assertThat(policy(false).isAllowed("http://acme.shopfi.test:3000")).isTrue();
        assertThat(policy(false).isAllowed("https://acme.shopfi.test:99999")).isFalse();
        assertThat(policy(false).isAllowed("https://acme.shopfi.test:0")).isFalse();
        assertThat(policy(true).isAllowed("http://acme.shopfi.test:3000")).isFalse();
    }

    @Test
    void customDomainOfActiveTenantIsAllowed_andNotWhenSuspended() {
        when(registry.findByCustomDomain("shop.acme.com")).thenReturn(Optional.of(tenant("acme", TenantStatus.ACTIVE, "shop.acme.com")));
        when(registry.findByCustomDomain("old.acme.com")).thenReturn(Optional.of(tenant("acme", TenantStatus.SUSPENDED, "old.acme.com")));
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://shop.acme.com")).isTrue();
        assertThat(p.isAllowed("https://SHOP.acme.com")).isTrue();
        assertThat(p.isAllowed("https://old.acme.com")).isFalse();
        assertThat(p.isAllowed("https://shop.acme.com.evil.com")).isFalse();
        // platform hosts and the base domain are never looked up as custom domains
        assertThat(p.isAllowed("https://api.shopfi.test")).isFalse();
        verify(registry, never()).findByCustomDomain("api.shopfi.test");
    }

    @Test
    void staticListIsExactAndWildcardFailsStartup() {
        cors.setAllowedOrigins(List.of("https://console.example.com", "http://localhost:3000"));
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://console.example.com")).isTrue();
        assertThat(p.isAllowed("http://localhost:3000")).isTrue();
        assertThat(p.isAllowed("http://localhost:3001")).isFalse();
        assertThat(p.isAllowed("https://console.example.com.evil.com")).isFalse();

        cors.setAllowedOrigins(List.of("*"));
        assertThatThrownBy(() -> policy(true)).isInstanceOf(IllegalStateException.class);
        cors.setAllowedOrigins(List.of("https://*.example.com"));
        assertThatThrownBy(() -> policy(true)).isInstanceOf(IllegalStateException.class);
        cors.setAllowedOrigins(List.of());
        cors.setAllowedHeaders(List.of("*"));
        assertThatThrownBy(() -> policy(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void positiveDecisionIsCachedUntilTtl_thenSuspensionFlips() {
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        verify(registry, times(1)).findBySlug("acme");

        when(registry.findBySlug("acme")).thenReturn(Optional.of(tenant("acme", TenantStatus.SUSPENDED, null)));
        now.addAndGet(29_000_000_000L);
        assertThat(p.isAllowed("https://acme.shopfi.test")).as("still inside the 30 s TTL").isTrue();
        now.addAndGet(2_000_000_000L);
        assertThat(p.isAllowed("https://acme.shopfi.test")).as("after the TTL").isFalse();
        verify(registry, times(2)).findBySlug("acme");
    }

    @Test
    void invalidateAllMakesSuspensionImmediate() {
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        when(registry.findBySlug("acme")).thenReturn(Optional.of(tenant("acme", TenantStatus.SUSPENDED, null)));
        p.invalidateAll();
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
    }

    @Test
    void negativeDecisionIsCachedAndBounded() {
        cors.getTenantCache().setNegativeMaxEntries(3);
        cors.getTenantCache().setMaxLookupsPerSecond(1000);
        TenantOriginPolicy p = policy(true);
        for (int i = 0; i < 10; i++) {
            assertThat(p.isAllowed("https://ghost.shopfi.test")).isFalse();
        }
        verify(registry, times(1)).findBySlug("ghost");
        // 5 distinct unknown slugs overflow the 3-entry denied cache; the positive entry survives the flood
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        for (int i = 0; i < 5; i++) {
            p.isAllowed("https://junk" + i + ".shopfi.test");
        }
        Mockito.clearInvocations(registry);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        verify(registry, never()).findBySlug("acme");
    }

    @Test
    void uniqueGarbageSlugFloodIsCappedByTheLookupBudget_andFailsClosed() {
        cors.getTenantCache().setMaxLookupsPerSecond(5);
        TenantOriginPolicy p = policy(true);
        for (int i = 0; i < 200; i++) {
            p.isAllowed("https://flood" + i + ".shopfi.test");
        }
        verify(registry, times(5)).findBySlug(Mockito.startsWith("flood"));
        // fail closed: even a real tenant is denied while the budget is empty, and the denial is not cached
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
        now.addAndGet(2_000_000_000L);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
    }

    @Test
    void resetBudgets_refillsTheSameCapacity_withoutChangingFailClosedBehaviour() {
        cors.getTenantCache().setMaxLookupsPerSecond(5);
        TenantOriginPolicy p = policy(true);
        for (int i = 0; i < 50; i++) {
            p.isAllowed("https://flood" + i + ".shopfi.test");
        }
        verify(registry, times(5)).findBySlug(Mockito.startsWith("flood"));
        // production semantics: the empty budget fails closed and invalidateAll() does NOT refill it
        p.invalidateAll();
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
        // test support: resetBudgets refills exactly the configured capacity (5), not more, and still fails closed after
        p.resetBudgets();
        Mockito.clearInvocations(registry);
        for (int i = 0; i < 50; i++) {
            p.isAllowed("https://again" + i + ".shopfi.test");
        }
        verify(registry, times(5)).findBySlug(Mockito.startsWith("again"));
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
        p.resetBudgets();
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
    }

    @Test
    void registryErrorFailsClosedAndIsNotCached() {
        when(registry.findBySlug("acme")).thenThrow(new RuntimeException("db down"))
                .thenReturn(Optional.of(tenant("acme", TenantStatus.ACTIVE, null)));
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
    }

    @Test
    void subdomainSlugRequiresExactlyOneValidLabel() {
        assertThat(TenantOriginPolicy.subdomainSlug("acme.shopfi.test", "shopfi.test")).isEqualTo("acme");
        assertThat(TenantOriginPolicy.subdomainSlug("a.b.shopfi.test", "shopfi.test")).isNull();
        assertThat(TenantOriginPolicy.subdomainSlug("www.shopfi.test", "shopfi.test")).isNull();
        assertThat(TenantOriginPolicy.subdomainSlug("ab.shopfi.test", "shopfi.test")).isNull();
        assertThat(TenantOriginPolicy.subdomainSlug("acme.shopfi.test", "")).isNull();
        assertThat(TenantOriginPolicy.subdomainSlug("acme.shopfi.test", null)).isNull();
    }

    // ---- DOMAIN-01b S2: per-client budget and protected refresh lane -----------------------------------------------

    private static final long SEC = 1_000_000_000L;

    @Test
    void oneClientFloodingUnknownSlugsCannotStarveOtherClientsOrAKnownTenantsRefresh() {
        cors.getTenantCache().setMaxLookupsPerSecond(50);
        cors.getTenantCache().setMaxLookupsPerIpPerSecond(5);
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test", "10.0.0.9")).isTrue();
        now.addAndGet(31 * SEC); // acme's 30 s entry expires
        // attacker: 500 unknown-slug lookups inside one second from ONE address
        for (int i = 0; i < 500; i++) {
            p.isAllowed("https://flood" + i + ".shopfi.test", "6.6.6.6");
            now.addAndGet(SEC / 1000);
        }
        verify(registry, atMost(7)).findBySlug(Mockito.startsWith("flood"));
        // a known tenant is refreshed from the protected lane and stays allowed on every request
        for (int i = 0; i < 100; i++) {
            assertThat(p.isAllowed("https://acme.shopfi.test", "10.0.0.9")).as("request %d", i).isTrue();
        }
        // another client's never-seen origin still gets its lookup: the global budget was not drained by one IP
        active("fresh");
        assertThat(p.isAllowed("https://fresh.shopfi.test", "7.7.7.7")).isTrue();
    }

    @Test
    void manyAddressFloodDegradesGracefully_knownTenantsStayAllowed_unknownKeysFailClosed() {
        cors.getTenantCache().setMaxLookupsPerSecond(10);
        cors.getTenantCache().setMaxLookupsPerIpPerSecond(10);
        cors.getTenantCache().setMaxRefreshesPerSecond(5);
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test", "1.1.1.1")).isTrue();
        now.addAndGet(31 * SEC);
        for (int i = 0; i < 400; i++) {
            p.isAllowed("https://m" + i + ".shopfi.test", "9.9." + (i / 250) + "." + (i % 250));
        }
        verify(registry, atMost(12)).findBySlug(Mockito.startsWith("m"));
        assertThat(p.isAllowed("https://acme.shopfi.test", "1.1.1.1")).as("refresh lane").isTrue();
        // documented limitation: a never-seen (new) tenant's first call can be denied while the flood lasts
        active("brandnew");
        assertThat(p.isAllowed("https://brandnew.shopfi.test", "2.2.2.2")).isFalse();
    }

    @Test
    void staleAllowIsServedWithinTheGraceWindowOnly_whenRefreshBudgetOrRegistryFails() {
        cors.getTenantCache().setMaxRefreshesPerSecond(1);
        cors.getTenantCache().setStaleGraceSeconds(60);
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        now.addAndGet(31 * SEC);
        when(registry.findBySlug("acme")).thenThrow(new RuntimeException("db down"));
        assertThat(p.isAllowed("https://acme.shopfi.test")).as("stale allow during an outage").isTrue();
        now.addAndGet(60 * SEC);
        assertThat(p.isAllowed("https://acme.shopfi.test")).as("past the grace window: fail closed").isFalse();
    }

    @Test
    void suspensionIsPickedUpByTheRefreshLane() {
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        when(registry.findBySlug("acme")).thenReturn(Optional.of(tenant("acme", TenantStatus.SUSPENDED, null)));
        now.addAndGet(31 * SEC);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
    }

    // ---- DOMAIN-01b S3: custom domain collides with a slug host ------------------------------------------------------

    @Test
    void customDomainUnderTheBaseDomainWinsOverTheSlug_likeTheResolutionFilter() {
        // tenant ydom owns custom_domain=acme.shopfi.test; the resolution filter would process the request as ydom
        when(registry.findByCustomDomain("acme.shopfi.test")).thenReturn(Optional.of(tenant("ydom", TenantStatus.SUSPENDED, "acme.shopfi.test")));
        assertThat(policy(true).isAllowed("https://acme.shopfi.test"))
                .as("origin must not be approved for ACTIVE acme while it resolves as suspended ydom").isFalse();
        when(registry.findByCustomDomain("acme.shopfi.test")).thenReturn(Optional.of(tenant("ydom", TenantStatus.ACTIVE, "acme.shopfi.test")));
        assertThat(policy(true).isAllowed("https://acme.shopfi.test")).isTrue();
    }

    // ---- DOMAIN-01b S4: invalidateAll() racing an in-flight lookup ------------------------------------------------

    // ---- stale lane under exhausted budgets (DOMAIN-01b S2 core) --------------------------------------------------

    @Test
    void recentlyAllowedTenantsStayAllowedWhileTheBudgetsAreExhausted_unseenHostsAndPastGraceAreDenied() {
        cors.getTenantCache().setMaxRefreshesPerSecond(1);
        cors.getTenantCache().setMaxLookupsPerSecond(3);
        cors.getTenantCache().setMaxLookupsPerIpPerSecond(3);
        cors.getTenantCache().setStaleGraceSeconds(60);
        active("bravo");
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test", "1.1.1.1")).isTrue();
        assertThat(p.isAllowed("https://bravo.shopfi.test", "1.1.1.1")).isTrue();
        now.addAndGet(31 * SEC); // both entries are past the 30 s TTL, inside the 60 s grace
        // attackers (one IP, then many IPs) exhaust the lookup budgets with never-seen hosts
        for (int i = 0; i < 50; i++) {
            p.isAllowed("https://junk" + i + ".shopfi.test", "6.6.6.6");
            p.isAllowed("https://junk" + (100 + i) + ".shopfi.test", "6.6." + i + ".1");
        }
        // the registry now says bravo is suspended, but the refresh budget (1 token) is spent by acme's refresh
        doReturn(Optional.of(tenant("bravo", TenantStatus.SUSPENDED, null))).when(registry).findBySlug("bravo");
        Mockito.clearInvocations(registry);
        assertThat(p.isAllowed("https://acme.shopfi.test", "1.1.1.1")).as("refreshed through the protected lane").isTrue();
        verify(registry, times(1)).findBySlug("acme");
        assertThat(p.isAllowed("https://bravo.shopfi.test", "1.1.1.1"))
                .as("refresh budget empty: the recently allowed tenant is served stale, NOT denied").isTrue();
        verify(registry, never()).findBySlug("bravo");
        // never-seen hosts fail closed while the budgets are empty
        active("brandnew");
        assertThat(p.isAllowed("https://brandnew.shopfi.test", "2.2.2.2")).isFalse();
        // past the grace window the stale ALLOW is gone: bravo is no longer served from cache
        now.addAndGet(61 * SEC);
        assertThat(p.isAllowed("https://bravo.shopfi.test", "1.1.1.1")).isFalse();
    }

    @Test
    void staleAllowSurvivesRegistryErrors_butUnseenHostsFailClosed() {
        cors.getTenantCache().setStaleGraceSeconds(60);
        TenantOriginPolicy p = policy(true);
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        now.addAndGet(31 * SEC);
        doThrow(new RuntimeException("db down")).when(registry).findBySlug(anyString());
        assertThat(p.isAllowed("https://acme.shopfi.test")).isTrue();
        assertThat(p.isAllowed("https://neverseen.shopfi.test")).isFalse();
    }

    // ---- DOMAIN-01b S4: deterministic invalidate race ------------------------------------------------------------

    @Test
    void invalidateAllWhileALookupIsInFlight_doesNotStoreThatResult_butLaterLookupsAreCached() throws Exception {
        java.util.concurrent.CountDownLatch inLookup = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            inLookup.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return Optional.of(tenant("acme", TenantStatus.ACTIVE, null)); // the row as read BEFORE the commit
        }).when(registry).findBySlug("acme");
        TenantOriginPolicy p = policy(true);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Boolean> inFlight = ex.submit(() -> p.isAllowed("https://acme.shopfi.test"));
            assertThat(inLookup.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // the suspension commits and the cache is invalidated while the lookup is still running
            doReturn(Optional.of(tenant("acme", TenantStatus.SUSPENDED, null))).when(registry).findBySlug("acme");
            p.invalidateAll();
            release.countDown();
            assertThat(inFlight.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("that request saw the old row").isTrue();
        } finally {
            ex.shutdownNow();
        }
        Mockito.clearInvocations(registry);
        assertThat(p.isAllowed("https://acme.shopfi.test")).as("the stale ACTIVE was NOT cached").isFalse();
        verify(registry, times(1)).findBySlug("acme");
        // a lookup started after the invalidation IS cached
        assertThat(p.isAllowed("https://acme.shopfi.test")).isFalse();
        verify(registry, times(1)).findBySlug("acme");
    }
}

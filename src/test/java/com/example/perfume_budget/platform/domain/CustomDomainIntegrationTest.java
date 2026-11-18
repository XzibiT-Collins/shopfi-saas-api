package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.tenantauth.AbstractTenantWebTest;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.security.oauth2.CustomOAuth2User;
import com.example.perfume_budget.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.example.perfume_budget.support.FakeDnsTxtResolver;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.tenancy.RequestTenantResolver;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * DOMAIN-02 on the REAL filter chain and PostgreSQL: the tenant-admin write path, DNS TXT verification (fake resolver),
 * the VERIFIED-only lookup honoured by CORS, tenant resolution, OAuth2 and reset links, uniqueness (case-insensitive,
 * database-enforced, concurrent claims), removal, cache invalidation, rate limits, platform-admin actions and the
 * append-only audit trail. Tenants acme and bravo; base domain shopfi.test. Redis is the base class's in-memory fake
 * (password-reset tokens).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example,api.shopfi.example",
        "cors.allowed-origins=https://static.example.com",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        // production-like cache TTLs: only invalidateAll() can explain an immediate flip
        "cors.tenant-cache.ttl-seconds=30",
        "cors.tenant-cache.negative-ttl-seconds=30",
        "cors.tenant-cache.max-lookups-per-second=1000",
        "cors.tenant-cache.max-lookups-per-ip-per-second=1000",
        "tenant.custom-domain.verify-per-tenant-per-hour=3",
        // low global DNS budget so the global limiter is observable (rework L7/R10); no test needs more than 5 checks
        "tenant.custom-domain.verify-global-per-minute=5",
        "tenant.custom-domain.cname-target=stores.shopfi.app",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0"
})
class CustomDomainIntegrationTest extends AbstractTenantWebTest {

    static final String ADMIN_PATH = "/api/v1/admin/custom-domain";
    static final String CORS_PATH = "/api/v1/category/all";
    static final String PLATFORM_PW = "Sup3r-Admin-pass!";
    static final AtomicInteger SEQ = new AtomicInteger();
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired CustomDomainService service;
    @Autowired FakeDnsTxtResolver dns;
    @Autowired TenantOriginPolicy originPolicy;
    @Autowired TenantRegistryService registry;
    @Autowired RequestTenantResolver resolver;
    @Autowired TenantLinkBuilder links;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformAdminRepository platformAdmins;
    @Autowired HttpCookieOAuth2AuthorizationRequestRepository authRequestRepo;
    @Autowired OAuth2AuthenticationSuccessHandler successHandler;
    @Autowired OAuth2AuthenticationFailureHandler failureHandler;
    @MockitoBean MailTransport mailTransport;

    Cookie acmeAdmin;
    Cookie bravoAdmin;

    /** Tenant-bound admin access token (tenant claim from TenantContext, as in production login). */
    private Cookie adminCookie(TestTenant tenant) {
        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT id, email, full_name FROM " + tenant.schemaName() + ".users WHERE email = ?", tenant.adminEmail());
        String token = tenants.runAs(tenant, () -> jwtUtil.generateAccessToken(
                (String) admin.get("email"), "ADMIN", ((Number) admin.get("id")).longValue(), (String) admin.get("full_name")));
        return new Cookie("accessToken", token);
    }

    @BeforeEach
    void setUp() {
        acme = tenants.acme();
        bravo = tenants.bravo();
        clearDomains(); // the base class gives acme/bravo VERIFIED fixture domains; this class starts without any
        acmeAdmin = adminCookie(acme);
        bravoAdmin = adminCookie(bravo);
        dns.reset();
        service.resetLimits();
        service.setClock(java.time.Clock.systemUTC());
        originPolicy.invalidateAll();
    }

    @AfterEach
    void tearDown() {
        clearDomains();
        dns.reset();
        originPolicy.invalidateAll();
        TenantContext.clear();
    }

    private void clearDomains() {
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, custom_domain_status = NULL, custom_domain_token = NULL, "
                + "custom_domain_requested_at = NULL, custom_domain_checked_at = NULL, custom_domain_verified_at = NULL, "
                + "custom_domain_last_verified_at = NULL, custom_domain_failure = NULL WHERE slug IN ('acme', 'bravo')");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(b).andReturn();
        return r.getResponse();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b, TestTenant t, Cookie admin) {
        return b.header("X-Tenant-ID", t.slug()).cookie(admin);
    }

    private MockHttpServletResponse claim(TestTenant t, Cookie admin, String domain) throws Exception {
        return call(asAdmin(put(ADMIN_PATH), t, admin).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("domain", domain))));
    }

    private MockHttpServletResponse verifyNow(TestTenant t, Cookie admin) throws Exception {
        return call(asAdmin(post(ADMIN_PATH + "/verify"), t, admin));
    }

    private MockHttpServletResponse removeNow(TestTenant t, Cookie admin) throws Exception {
        return call(asAdmin(delete(ADMIN_PATH), t, admin));
    }

    private static JsonNode data(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString()).get("data");
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        JsonNode d = data(r);
        return d == null || d.isNull() ? null : d.path("code").asText(null);
    }

    /** Claims and verifies {@code domain} for {@code t} through the API, publishing the TXT proof in the fake DNS. */
    private JsonNode claimAndVerify(TestTenant t, Cookie admin, String domain) throws Exception {
        MockHttpServletResponse c = claim(t, admin, domain);
        assertThat(c.getStatus()).as(c.getContentAsString()).isEqualTo(200);
        JsonNode d = data(c);
        dns.put(d.get("txtRecordName").asText(), d.get("txtRecordValue").asText());
        MockHttpServletResponse v = verifyNow(t, admin);
        assertThat(v.getStatus()).as(v.getContentAsString()).isEqualTo(200);
        assertThat(data(v).get("status").asText()).isEqualTo("VERIFIED");
        return data(v);
    }

    /** CORS preflight; returns the Access-Control-Allow-Origin header (null = denied). */
    private String preflight(String origin) throws Exception {
        MockHttpServletResponse r = call(options(CORS_PATH).header("Origin", origin)
                .header("Access-Control-Request-Method", "GET"));
        return r.getHeader("Access-Control-Allow-Origin");
    }

    /** The tenant the real resolver (used by TenantResolutionFilter and the WS handshake) binds for this Origin. */
    private String resolvedSlug(String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", CORS_PATH);
        req.addHeader("Origin", origin);
        RequestTenantResolver.Resolution r = resolver.resolve(req);
        return r.identity() == null ? r.outcome().name() : r.identity().slug();
    }

    private List<Map<String, Object>> auditRows(String slug) {
        return jdbc.queryForList("SELECT * FROM master.tenant_domain_audit_log WHERE tenant_slug = ? ORDER BY id", slug);
    }

    private long auditMark() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM master.tenant_domain_audit_log", Long.class);
        return max == null ? 0 : max;
    }

    private List<Map<String, Object>> auditSince(long mark, String slug) {
        return jdbc.queryForList("SELECT * FROM master.tenant_domain_audit_log WHERE id > ? AND tenant_slug = ? ORDER BY id",
                mark, slug);
    }

    private Map<String, Object> row(String slug) {
        return jdbc.queryForMap("SELECT custom_domain, custom_domain_status, custom_domain_token, custom_domain_failure "
                + "FROM master.tenants WHERE slug = ?", slug);
    }

    private Cookie platformLogin() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "dom02root" + n + "-" + System.nanoTime() + "@platform.test";
        platformAdmins.save(PlatformAdmin.builder().email(email).password(encoder.encode(PLATFORM_PW))
                .fullName("Root").isActive(true).build());
        MockHttpServletResponse r = call(post("/api/v1/platform/auth/login")
                .with(req -> { req.setRemoteAddr("10.82." + (n / 250) + "." + (n % 250 + 1)); return req; })
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PLATFORM_PW + "\"}"));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Cookie("platformAccessToken", r.getCookie("platformAccessToken").getValue());
    }

    // ---- AC1 + AC2: lifecycle, VERIFIED-only honouring, removal and cache invalidation --------------------------------

    @Test
    void lifecycle_pendingAndFailedAreNeverHonoured_verifiedIsHonouredEverywhere_removalRevokesAtOnce() throws Exception {
        String origin = "https://shop.acme-brand.com";
        long mark = auditMark();

        MockHttpServletResponse c = claim(acme, acmeAdmin, "  Shop.ACME-Brand.com. ");
        assertThat(c.getStatus()).as(c.getContentAsString()).isEqualTo(200);
        JsonNode d = data(c);
        assertThat(d.get("domain").asText()).isEqualTo("shop.acme-brand.com");
        assertThat(d.get("status").asText()).isEqualTo("PENDING");
        assertThat(d.get("txtRecordName").asText()).isEqualTo("_shopfi-verification.shop.acme-brand.com");
        assertThat(d.get("txtRecordValue").asText()).matches("shopfi-verification=[A-Za-z0-9_-]{32}");
        assertThat(d.get("cnameTarget").asText()).isEqualTo("stores.shopfi.app");
        assertThat(row("acme").get("custom_domain")).isEqualTo("shop.acme-brand.com");

        // PENDING: not honoured by the shared lookup, CORS, resolution or links
        assertThat(registry.findByVerifiedCustomDomain("shop.acme-brand.com")).isEmpty();
        assertThat(preflight(origin)).isNull();
        assertThat(resolvedSlug(origin)).isEqualTo("UNKNOWN");
        assertThat(links.origin("acme")).contains("https://acme.shopfi.test");

        // the idempotent re-claim keeps the token (the published TXT record stays valid)
        assertThat(data(claim(acme, acmeAdmin, "shop.acme-brand.com")).get("txtRecordValue").asText())
                .isEqualTo(d.get("txtRecordValue").asText());

        // FAILED (no TXT record): still not honoured
        MockHttpServletResponse failed = verifyNow(acme, acmeAdmin);
        assertThat(failed.getStatus()).isEqualTo(200);
        assertThat(data(failed).get("status").asText()).isEqualTo("FAILED");
        assertThat(data(failed).get("failureReason").asText()).isEqualTo("TXT_RECORD_NOT_FOUND");
        assertThat(preflight(origin)).isNull();
        assertThat(resolvedSlug(origin)).isEqualTo("UNKNOWN");

        // a wrong value is not a proof either
        dns.put("_shopfi-verification.shop.acme-brand.com", "shopfi-verification=someone-elses-token", "v=spf1 -all");
        assertThat(data(verifyNow(acme, acmeAdmin)).get("status").asText()).isEqualTo("FAILED");

        // VERIFIED: honoured by everything
        dns.put("_shopfi-verification.shop.acme-brand.com", "v=spf1 -all", d.get("txtRecordValue").asText());
        MockHttpServletResponse ok = verifyNow(acme, acmeAdmin);
        assertThat(data(ok).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(data(ok).get("verifiedAt").isNull()).isFalse();
        assertThat(data(ok).get("failureReason").isNull()).isTrue();
        assertThat(registry.findByVerifiedCustomDomain("SHOP.acme-brand.com")).get().extracting(t -> t.getSlug()).isEqualTo("acme");
        assertThat(preflight(origin)).isEqualTo(origin);
        assertThat(resolvedSlug(origin)).isEqualTo("acme");
        assertThat(links.origin("acme")).contains("https://shop.acme-brand.com");

        // removal: the allowed decision cached a moment ago (TTL 30 s) is dropped at once
        assertThat(preflight(origin)).isEqualTo(origin);
        MockHttpServletResponse removed = removeNow(acme, acmeAdmin);
        assertThat(removed.getStatus()).isEqualTo(200);
        assertThat(data(removed).get("domain").isNull()).isTrue();
        assertThat(data(removed).get("cnameTarget").asText()).isEqualTo("stores.shopfi.app");
        assertThat(preflight(origin)).as("cache invalidated by the removal").isNull();
        assertThat(resolvedSlug(origin)).isEqualTo("UNKNOWN");
        assertThat(links.origin("acme")).contains("https://acme.shopfi.test");
        assertThat(row("acme").get("custom_domain")).isNull();
        assertThat(removeNow(acme, acmeAdmin).getStatus()).as("idempotent").isEqualTo(200);

        List<Map<String, Object>> audit = auditSince(mark, "acme");
        assertThat(audit).extracting(r -> r.get("action") + "/" + r.get("new_status") + "/" + r.get("detail")).containsExactly(
                "REQUEST/PENDING/null", "VERIFY/FAILED/TXT_RECORD_NOT_FOUND", "VERIFY/FAILED/TXT_RECORD_NOT_FOUND",
                "VERIFY/VERIFIED/TXT_RECORD_MATCHED", "REMOVE/null/null");
        assertThat(audit).allSatisfy(r -> {
            assertThat(r.get("actor_type")).isEqualTo("TENANT_ADMIN");
            assertThat(r.get("actor_email")).isEqualTo(acme.adminEmail());
            assertThat(r.get("domain")).isEqualTo("shop.acme-brand.com");
        });
        assertThat(JSON.writeValueAsString(audit)).as("no token in the audit trail")
                .doesNotContain(d.get("txtRecordValue").asText().substring("shopfi-verification=".length()));
    }

    @Test
    void reverification_aVerifiedDomainWhoseProofDisappearedIsRevoked_andCorsFlipsAtOnce() throws Exception {
        String origin = "https://www.bravo-goods.net";
        JsonNode v = claimAndVerify(bravo, bravoAdmin, "www.bravo-goods.net");
        assertThat(preflight(origin)).isEqualTo(origin);
        assertThat(resolvedSlug(origin)).isEqualTo("bravo");

        dns.remove(v.get("txtRecordName").asText());
        MockHttpServletResponse again = verifyNow(bravo, bravoAdmin);
        assertThat(data(again).get("status").asText()).isEqualTo("FAILED");
        assertThat(data(again).get("verifiedAt").isNull()).isTrue();
        assertThat(preflight(origin)).as("cache invalidated by the failed re-verification").isNull();
        assertThat(resolvedSlug(origin)).isEqualTo("UNKNOWN");

        // replacing a VERIFIED domain with another one: the old one stops being honoured at once
        dns.put(v.get("txtRecordName").asText(), v.get("txtRecordValue").asText());
        assertThat(data(verifyNow(bravo, bravoAdmin)).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(preflight(origin)).isEqualTo(origin);
        MockHttpServletResponse replaced = claim(bravo, bravoAdmin, "shop.bravo-goods.net");
        assertThat(data(replaced).get("status").asText()).isEqualTo("PENDING");
        assertThat(data(replaced).get("txtRecordValue").asText()).isNotEqualTo(v.get("txtRecordValue").asText());
        assertThat(preflight(origin)).isNull();
        assertThat(preflight("https://shop.bravo-goods.net")).isNull();
        assertThat(auditRows("bravo")).last().satisfies(r -> {
            assertThat(r.get("action")).isEqualTo("REQUEST");
            assertThat(r.get("previous_domain")).isEqualTo("www.bravo-goods.net");
            assertThat(r.get("previous_status")).isEqualTo("VERIFIED");
            assertThat(r.get("detail")).isEqualTo("REPLACED");
        });
    }

    // ---- AC1: validation through the API (one call per code) and the reproduced collision ---------------------------

    @Test
    void validationErrorsUseTheEnvelopeWithDataCode_andWriteNothing() throws Exception {
        long mark = auditMark();
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("acme.shopfi.test", "DOMAIN_PLATFORM");          // the ydom repro: bravo claiming acme's subdomain
        cases.put("api.shopfi.example", "DOMAIN_PLATFORM");
        cases.put("console.shopfi.example", "DOMAIN_PLATFORM");
        cases.put("10.0.0.7", "DOMAIN_IP_LITERAL");
        cases.put("[::1]", "DOMAIN_IP_LITERAL");
        cases.put("0x7f000001", "DOMAIN_IP_LITERAL");
        cases.put("localhost", "DOMAIN_RESERVED");
        cases.put("shop.internal", "DOMAIN_RESERVED");
        cases.put("shоpfi.example", "DOMAIN_CONFUSABLE");
        cases.put("https://shop.bravo-goods.net/", "DOMAIN_INVALID");
        cases.put("shop", "DOMAIN_INVALID");
        cases.put(" ", "DOMAIN_REQUIRED");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            MockHttpServletResponse r = claim(bravo, bravoAdmin, e.getKey());
            assertThat(r.getStatus()).as(e.getKey()).isEqualTo(400);
            assertThat(code(r)).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(JSON.readTree(r.getContentAsString()).get("description").asText()).isNotBlank();
        }
        MockHttpServletResponse noBody = call(asAdmin(put(ADMIN_PATH), bravo, bravoAdmin).contentType("application/json").content("{}"));
        assertThat(code(noBody)).isEqualTo("DOMAIN_REQUIRED");
        assertThat(row("bravo").get("custom_domain")).isNull();
        assertThat(auditSince(mark, "bravo")).isEmpty();
        assertThat(resolvedSlug("https://acme.shopfi.test")).isEqualTo("acme");
    }

    @Test
    void legacyDirectDbEdit_ydomCollision_isNeverHonoured_andCanNeverBecomeVerified() throws Exception {
        // the DOMAIN-01 reproduction, written around the API as before DOMAIN-02 (direct SQL), even with a valid TXT proof
        jdbc.update("UPDATE master.tenants SET custom_domain = 'acme.shopfi.test', custom_domain_status = 'PENDING', "
                + "custom_domain_token = 'legacy-token', custom_domain_requested_at = now() WHERE slug = 'bravo'");
        dns.put("_shopfi-verification.acme.shopfi.test", "shopfi-verification=legacy-token");
        MockHttpServletResponse v = verifyNow(bravo, bravoAdmin);
        assertThat(v.getStatus()).isEqualTo(200);
        assertThat(data(v).get("status").asText()).isEqualTo("FAILED");
        assertThat(data(v).get("failureReason").asText()).isEqualTo("DOMAIN_PLATFORM");
        assertThat(dns.lookups()).as("an invalid stored domain is never even looked up").isZero();
        assertThat(resolvedSlug("https://acme.shopfi.test")).isEqualTo("acme");
        assertThat(preflight("https://acme.shopfi.test")).isEqualTo("https://acme.shopfi.test");

        // forced VERIFIED by SQL: the host is under the base domain, so it is still acme's (slug only)
        jdbc.update("UPDATE master.tenants SET custom_domain_status = 'VERIFIED' WHERE slug = 'bravo'");
        originPolicy.invalidateAll();
        assertThat(resolvedSlug("https://acme.shopfi.test")).isEqualTo("acme");
        jdbc.update("UPDATE master.tenants SET status = 'SUSPENDED' WHERE slug = 'bravo'");
        try {
            assertThat(preflight("https://acme.shopfi.test")).as("decided for acme, not for suspended bravo")
                    .isEqualTo("https://acme.shopfi.test");
        } finally {
            jdbc.update("UPDATE master.tenants SET status = 'ACTIVE' WHERE slug = 'bravo'");
        }
    }

    // ---- AC1: uniqueness -------------------------------------------------------------------------------------------

    @Test
    void uniquenessIsCaseInsensitive_andEnforcedByTheDatabaseToo() throws Exception {
        assertThat(claim(acme, acmeAdmin, "shop.shared-brand.com").getStatus()).isEqualTo(200);
        MockHttpServletResponse dup = claim(bravo, bravoAdmin, "SHOP.Shared-Brand.COM");
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("DOMAIN_TAKEN");
        assertThat(dup.getContentAsString()).doesNotContain("acme");
        assertThat(row("bravo").get("custom_domain")).isNull();

        // the master migration enforces it without the service: lower-case unique index + shape checks
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain = 'shop.shared-brand.com', "
                + "custom_domain_status = 'PENDING', custom_domain_token = 'x' WHERE slug = 'bravo'"))
                .hasMessageContaining("uq_tenants_custom_domain_lower");
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain = 'SHOP.Shared-Brand.com', "
                + "custom_domain_status = 'PENDING', custom_domain_token = 'x' WHERE slug = 'bravo'"))
                .hasMessageContaining("ck_tenants_custom_domain_shape");
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain = 'other.shared-brand.com' "
                + "WHERE slug = 'bravo'")).hasMessageContaining("ck_tenants_custom_domain_state");
        assertThatThrownBy(() -> jdbc.update("UPDATE master.tenants SET custom_domain = 'other.shared-brand.com', "
                + "custom_domain_status = 'ACTIVE', custom_domain_token = 'x' WHERE slug = 'bravo'"))
                .hasMessageContaining("ck_tenants_custom_domain_status");
    }

    @Test
    void concurrentClaimsOfOneDomain_exactlyOneWins_theOtherGetsAClean409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                clearDomains();
                service.resetLimits();
                String domain = "race" + round + ".shared-brand.com";
                long mark = auditMark();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<MockHttpServletResponse> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return claim(acme, acmeAdmin, domain); });
                Future<MockHttpServletResponse> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return claim(bravo, bravoAdmin, domain); });
                MockHttpServletResponse ra = a.get(30, TimeUnit.SECONDS);
                MockHttpServletResponse rb = b.get(30, TimeUnit.SECONDS);
                List<Integer> statuses = new ArrayList<>(List.of(ra.getStatus(), rb.getStatus()));
                statuses.sort(null);
                assertThat(statuses).as("round %d: %s / %s", round, ra.getContentAsString(), rb.getContentAsString())
                        .containsExactly(200, 409);
                MockHttpServletResponse loser = ra.getStatus() == 409 ? ra : rb;
                String winner = ra.getStatus() == 200 ? "acme" : "bravo";
                assertThat(code(loser)).isEqualTo("DOMAIN_TAKEN");
                assertThat(jdbc.queryForList("SELECT slug FROM master.tenants WHERE lower(custom_domain) = ?", String.class, domain))
                        .containsExactly(winner);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM master.tenant_domain_audit_log WHERE id > ? AND domain = ?",
                        Integer.class, mark, domain)).as("only the winner is audited").isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lostRaceDetectedByTheUniqueIndexItself_mapsTo409_andARolledBackRivalLetsTheClaimWin() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection rival = PostgresTestDatabase.connect()) {
            rival.setAutoCommit(false);
            try (Statement st = rival.createStatement()) {
                st.execute("SET lock_timeout = '10s'");
            }
            // 1) the rival (bravo) holds an uncommitted claim; acme's pre-check cannot see it, so acme's write waits on
            //    the unique index and then fails with 23505 when the rival commits: still a clean 409
            try (PreparedStatement ps = rival.prepareStatement("UPDATE master.tenants SET custom_domain = ?, "
                    + "custom_domain_status = 'PENDING', custom_domain_token = 'rival', custom_domain_requested_at = now() WHERE slug = 'bravo'")) {
                ps.setString(1, "index-race.shared-brand.com");
                ps.executeUpdate();
            }
            Future<MockHttpServletResponse> f = pool.submit(() -> claim(acme, acmeAdmin, "index-race.shared-brand.com"));
            Thread.sleep(700);
            assertThat(f.isDone()).as("acme waits on the rival's uncommitted index entry").isFalse();
            rival.commit();
            MockHttpServletResponse r = f.get(20, TimeUnit.SECONDS);
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("DOMAIN_TAKEN");

            // 2) the rival rolls back: acme's waiting claim wins
            try (PreparedStatement ps = rival.prepareStatement("UPDATE master.tenants SET custom_domain = ?, "
                    + "custom_domain_status = 'PENDING', custom_domain_token = 'rival2', custom_domain_requested_at = now() WHERE slug = 'bravo'")) {
                ps.setString(1, "index-race2.shared-brand.com");
                ps.executeUpdate();
            }
            Future<MockHttpServletResponse> g = pool.submit(() -> claim(acme, acmeAdmin, "index-race2.shared-brand.com"));
            Thread.sleep(700);
            rival.rollback();
            MockHttpServletResponse r2 = g.get(20, TimeUnit.SECONDS);
            assertThat(r2.getStatus()).as(r2.getContentAsString()).isEqualTo(200);
            assertThat(row("acme").get("custom_domain")).isEqualTo("index-race2.shared-brand.com");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleUnverifiedClaimIsReleasedToANewClaimant_freshOrVerifiedClaimsAreNot() throws Exception {
        assertThat(claim(bravo, bravoAdmin, "shop.squat-brand.com").getStatus()).isEqualTo(200);
        assertThat(claim(acme, acmeAdmin, "shop.squat-brand.com").getStatus()).as("fresh claim is protected").isEqualTo(409);

        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '100 hours' WHERE slug = 'bravo'");
        long mark = auditMark();
        MockHttpServletResponse taken = claim(acme, acmeAdmin, "shop.squat-brand.com");
        assertThat(taken.getStatus()).as(taken.getContentAsString()).isEqualTo(200);
        assertThat(row("bravo").get("custom_domain")).isNull();
        assertThat(row("bravo").get("custom_domain_token")).isNull();
        assertThat(auditSince(mark, "bravo")).singleElement().satisfies(r -> {
            assertThat(r.get("action")).isEqualTo("RELEASE");
            assertThat(r.get("actor_type")).isEqualTo("SYSTEM");
            assertThat(r.get("actor_id")).isNull();
            assertThat(r.get("previous_status")).isEqualTo("PENDING");
            assertThat(r.get("detail")).isEqualTo("UNVERIFIED_CLAIM_EXPIRED");
        });

        // a VERIFIED domain is never taken over, however old its claim
        JsonNode d = data(claim(acme, acmeAdmin, "shop.squat-brand.com"));
        dns.put(d.get("txtRecordName").asText(), d.get("txtRecordValue").asText());
        assertThat(data(verifyNow(acme, acmeAdmin)).get("status").asText()).isEqualTo("VERIFIED");
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '1000 hours', "
                + "custom_domain_last_verified_at = now() - interval '900 hours' WHERE slug = 'acme'");
        assertThat(claim(bravo, bravoAdmin, "shop.squat-brand.com").getStatus()).isEqualTo(409);
        assertThat(row("acme").get("custom_domain_status")).isEqualTo("VERIFIED");
    }

    // ---- AC2: DNS failure and rate limit -------------------------------------------------------------------------

    @Test
    void dnsWithoutADefinitiveAnswer_is503WithRetryAfter_andChangesNothing() throws Exception {
        JsonNode d = data(claim(acme, acmeAdmin, "shop.flaky-dns.com"));
        dns.fail(d.get("txtRecordName").asText());
        long mark = auditMark();
        MockHttpServletResponse r = verifyNow(acme, acmeAdmin);
        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(code(r)).isEqualTo("DOMAIN_DNS_UNAVAILABLE");
        assertThat(r.getHeader("Retry-After")).isEqualTo("30");
        assertThat(row("acme").get("custom_domain_status")).isEqualTo("PENDING");
        assertThat(auditSince(mark, "acme")).isEmpty();
    }

    @Test
    void verificationAttemptsAreRateLimitedPerTenant() throws Exception {
        claim(acme, acmeAdmin, "shop.limited-brand.com");
        for (int i = 0; i < 3; i++) {
            assertThat(verifyNow(acme, acmeAdmin).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = verifyNow(acme, acmeAdmin);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("DOMAIN_VERIFY_RATE_LIMITED");
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(dns.lookups()).as("a limited attempt never reaches DNS").isEqualTo(3);
        // another tenant's budget is independent
        claim(bravo, bravoAdmin, "shop.limited-other.com");
        assertThat(verifyNow(bravo, bravoAdmin).getStatus()).isEqualTo(200);
    }

    @Test
    void verifyWithoutADomainIs404DomainNotSet() throws Exception {
        MockHttpServletResponse r = verifyNow(acme, acmeAdmin);
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(code(r)).isEqualTo("DOMAIN_NOT_SET");
        MockHttpServletResponse g = call(asAdmin(get(ADMIN_PATH), acme, acmeAdmin));
        assertThat(g.getStatus()).isEqualTo(200);
        assertThat(data(g).get("domain").isNull()).isTrue();
    }

    // ---- authorization -------------------------------------------------------------------------------------------

    @Test
    void onlyTheTenantAdminMayUseTheTenantEndpoints() throws Exception {
        assertThat(call(put(ADMIN_PATH).header("X-Tenant-ID", "acme").contentType("application/json")
                .content("{\"domain\":\"shop.anon-brand.com\"}")).getStatus()).isEqualTo(401);
        // bravo's admin token presented on acme is anonymous there (AUTH-01)
        assertThat(claim(acme, bravoAdmin, "shop.foreign-brand.com").getStatus()).isEqualTo(401);
        String email = "dom02-customer@acme.test";
        User customer = tenants.runAs(acme, () -> userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName("Cust").email(email).password(encoder.encode("Custpass1!a")).roles(UserRole.CUSTOMER)
                .authProvider(AuthProvider.LOCAL).isActive(true).emailVerified(true).build())));
        Cookie customerCookie = new Cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken(
                email, "CUSTOMER", customer.getId(), "Cust")));
        assertThat(claim(acme, customerCookie, "shop.customer-brand.com").getStatus()).isEqualTo(403);
        assertThat(row("acme").get("custom_domain")).isNull();
    }

    // ---- platform admin ------------------------------------------------------------------------------------------

    @Test
    void platformAdmin_readsStateAndHistory_reverifies_andRemoves() throws Exception {
        Cookie platform = platformLogin();
        String base = "/api/v1/platform/tenants/acme/custom-domain";
        String origin = "https://shop.platform-brand.com";
        JsonNode v = claimAndVerify(acme, acmeAdmin, "shop.platform-brand.com");

        MockHttpServletResponse g = call(get(base).cookie(platform).header("Origin", "https://console.shopfi.example"));
        assertThat(g.getStatus()).as(g.getContentAsString()).isEqualTo(200);
        JsonNode pd = data(g);
        assertThat(pd.get("tenantSlug").asText()).isEqualTo("acme");
        assertThat(pd.get("customDomain").get("status").asText()).isEqualTo("VERIFIED");
        assertThat(pd.get("history").get(0).get("action").asText()).as("newest first").isEqualTo("VERIFY");
        assertThat(pd.get("history").size()).isGreaterThanOrEqualTo(2);

        MockHttpServletResponse list = call(get("/api/v1/platform/tenants").param("search", "acme").cookie(platform));
        assertThat(list.getContentAsString()).contains("\"customDomainStatus\":\"VERIFIED\"");

        dns.remove(v.get("txtRecordName").asText());
        assertThat(preflight(origin)).isEqualTo(origin);
        MockHttpServletResponse pv = call(post(base + "/verify").cookie(platform));
        assertThat(pv.getStatus()).isEqualTo(200);
        assertThat(data(pv).get("status").asText()).isEqualTo("FAILED");
        assertThat(preflight(origin)).isNull();

        dns.put(v.get("txtRecordName").asText(), v.get("txtRecordValue").asText());
        assertThat(data(call(post(base + "/verify").cookie(platform))).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(preflight(origin)).isEqualTo(origin);
        MockHttpServletResponse pdel = call(delete(base).cookie(platform));
        assertThat(pdel.getStatus()).isEqualTo(200);
        assertThat(preflight(origin)).isNull();
        assertThat(row("acme").get("custom_domain")).isNull();

        List<Map<String, Object>> audit = auditRows("acme");
        assertThat(audit.subList(audit.size() - 3, audit.size()))
                .extracting(r -> r.get("actor_type") + "/" + r.get("action") + "/" + r.get("new_status"))
                .containsExactly("PLATFORM_ADMIN/VERIFY/FAILED", "PLATFORM_ADMIN/VERIFY/VERIFIED", "PLATFORM_ADMIN/REMOVE/null");

        assertThat(call(get("/api/v1/platform/tenants/no-such-shop/custom-domain").cookie(platform)).getStatus()).isEqualTo(404);
        assertThat(call(get("/api/v1/platform/tenants/BadSlug/custom-domain").cookie(platform)).getStatus()).isEqualTo(404);
        assertThat(call(get(base)).getStatus()).as("anonymous").isEqualTo(401);
        assertThat(call(asAdmin(get(base), acme, acmeAdmin)).getStatus()).as("tenant admin token").isEqualTo(401);
    }

    @Test
    void auditTrailIsAppendOnly() throws Exception {
        claim(acme, acmeAdmin, "shop.audit-brand.com");
        for (String sql : List.of("UPDATE master.tenant_domain_audit_log SET detail = 'x'",
                "DELETE FROM master.tenant_domain_audit_log", "TRUNCATE master.tenant_domain_audit_log")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).hasMessageContaining("append-only");
        }
    }

    // ---- AC4: OAuth2 redirect URIs and reset links with a verified custom domain, end to end ------------------------

    private MockHttpServletRequest authorize(String redirectUri) throws Exception {
        MvcResult r = mvc.perform(get("/oauth2/authorize/google").param("redirect_uri", redirectUri)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        String cookie = null;
        for (String h : r.getResponse().getHeaders("Set-Cookie")) {
            if (h.startsWith("OAUTH2_AUTH_REQUEST=")) {
                cookie = h.substring("OAUTH2_AUTH_REQUEST=".length(), h.indexOf(';'));
            }
        }
        assertThat(cookie).isNotBlank();
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/oauth2/callback/google");
        callback.setCookies(new Cookie("OAUTH2_AUTH_REQUEST", cookie));
        return callback;
    }

    private String resetLink(TestTenant t, String email) throws Exception {
        Mockito.clearInvocations(mailTransport);
        MockHttpServletResponse r = call(post("/api/v1/auth/forgot-password").header("X-Tenant-ID", t.slug())
                .contentType("application/json").content("{\"email\":\"" + email + "\"}"));
        assertThat(r.getStatus()).isEqualTo(200);
        ArgumentCaptor<MailMessage> c = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailTransport, timeout(8000)).send(c.capture());
        Matcher m = Pattern.compile("(https?://[^/\"]+)/reset-password\\?resetPasswordToken=[A-Za-z0-9_-]+").matcher(c.getValue().html());
        assertThat(m.find()).as("reset link in the email").isTrue();
        return m.group(1);
    }

    private User shopper(TestTenant t, String email) {
        return tenants.runAs(t, () -> userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName("Shopper").email(email).password(encoder.encode("Shopper1!x")).roles(UserRole.CUSTOMER)
                .authProvider(AuthProvider.LOCAL).isActive(true).emailVerified(true).build())));
    }

    @Test
    void ac4_oauth2AndResetLinks_followAVerifiedCustomDomain_andIgnoreAPendingOne() throws Exception {
        claimAndVerify(acme, acmeAdmin, "shop.oauth-brand.com");
        assertThat(claim(bravo, bravoAdmin, "shop.pending-brand.com").getStatus()).isEqualTo(200); // stays PENDING

        // OAuth2 (Google): the verified custom domain is a trusted redirect_uri and restores acme on the callback
        String redirect = "https://shop.oauth-brand.com/oauth2/redirect";
        MockHttpServletRequest callback = authorize(redirect);
        assertThat(authRequestRepo.removeAuthorizationRequest(callback, new MockHttpServletResponse())).isNotNull();
        assertThat(TenantContext.getSlug()).isEqualTo("acme");
        assertThat(callback.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE)).isEqualTo(redirect);
        User acmeAdminUser = tenants.runAs(acme, () -> userRepository.findByEmail(acme.adminEmail()).orElseThrow());
        TenantContext.set(com.example.perfume_budget.tenancy.TenantIdentity.tenant("acme", "tenant_acme"));
        MockHttpServletResponse ok = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(callback, ok, new TestingAuthenticationToken(new CustomOAuth2User(acmeAdminUser, Map.of()), null));
        assertThat(ok.getRedirectedUrl()).startsWith(redirect).contains("success=true");
        // a failed login without a stashed redirect goes to the tenant's login page on its verified custom domain
        MockHttpServletResponse fail = new MockHttpServletResponse();
        failureHandler.onAuthenticationFailure(new MockHttpServletRequest(), fail,
                new OAuth2AuthenticationException(new OAuth2Error("x")));
        assertThat(fail.getRedirectedUrl()).startsWith("https://shop.oauth-brand.com/login");
        TenantContext.clear();

        // the PENDING custom domain is not a trusted redirect_uri: no tenant restored, nothing stashed
        MockHttpServletRequest pending = authorize("https://shop.pending-brand.com/oauth2/redirect");
        authRequestRepo.removeAuthorizationRequest(pending, new MockHttpServletResponse());
        assertThat(TenantContext.get()).isNull();
        assertThat(pending.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE)).isNull();
        TenantContext.set(com.example.perfume_budget.tenancy.TenantIdentity.tenant("bravo", "tenant_bravo"));
        MockHttpServletResponse bravoFail = new MockHttpServletResponse();
        failureHandler.onAuthenticationFailure(new MockHttpServletRequest(), bravoFail,
                new OAuth2AuthenticationException(new OAuth2Error("x")));
        assertThat(bravoFail.getRedirectedUrl()).startsWith("https://bravo.shopfi.test/login");
        TenantContext.clear();

        // reset links: verified custom domain for acme, slug.base-domain for bravo (PENDING)
        String acmeEmail = "dom02-reset-" + SEQ.incrementAndGet() + "@x.test";
        String bravoEmail = "dom02-reset-" + SEQ.incrementAndGet() + "@x.test";
        shopper(acme, acmeEmail);
        shopper(bravo, bravoEmail);
        assertThat(resetLink(acme, acmeEmail)).isEqualTo("https://shop.oauth-brand.com");
        assertThat(resetLink(bravo, bravoEmail)).isEqualTo("https://bravo.shopfi.test");

        // after removal acme's links fall back to its subdomain
        removeNow(acme, acmeAdmin);
        String acmeEmail2 = "dom02-reset-" + SEQ.incrementAndGet() + "@x.test";
        shopper(acme, acmeEmail2);
        assertThat(resetLink(acme, acmeEmail2)).isEqualTo("https://acme.shopfi.test");
    }

    // ---- rework round 1 ------------------------------------------------------------------------------------------

    private static final CustomDomainService.Actor SERVICE_ACTOR =
            new CustomDomainService.Actor(CustomDomainService.ActorType.TENANT_ADMIN, 1L, "rework@acme.test");

    /** F1 (review probe p3): a domain VERIFIED until a moment ago is not a stale claim, however old the claim is. */
    @Test
    void f1_aLongVerifiedDomainThatJustFailedReverification_cannotBeTakenOver_andTheOwnerRecovers() throws Exception {
        String domain = "shop.long-verified.com";
        JsonNode v = claimAndVerify(acme, acmeAdmin, domain);
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '2000 hours', "
                + "custom_domain_verified_at = now() - interval '1999 hours' WHERE slug = 'acme'");
        dns.remove(v.get("txtRecordName").asText());
        assertThat(data(verifyNow(acme, acmeAdmin)).get("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT custom_domain_last_verified_at IS NOT NULL FROM master.tenants WHERE slug = 'acme'",
                Boolean.class)).as("last VERIFIED time survives the failure").isTrue();

        MockHttpServletResponse squat = claim(bravo, bravoAdmin, domain);
        assertThat(squat.getStatus()).as(squat.getContentAsString()).isEqualTo(409);
        assertThat(code(squat)).isEqualTo("DOMAIN_TAKEN");
        assertThat(row("bravo").get("custom_domain")).isNull();

        dns.put(v.get("txtRecordName").asText(), v.get("txtRecordValue").asText());
        assertThat(data(verifyNow(acme, acmeAdmin)).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(row("acme").get("custom_domain")).isEqualTo(domain);
    }

    /** F1 boundary: once the last VERIFIED time is past the TTL as well, the FAILED claim is releasable again. */
    @Test
    void f1_aFailedClaimWhoseLastVerificationIsOlderThanTheTtl_isReleasable() throws Exception {
        String domain = "shop.lapsed-brand.com";
        JsonNode v = claimAndVerify(acme, acmeAdmin, domain);
        dns.remove(v.get("txtRecordName").asText());
        assertThat(data(verifyNow(acme, acmeAdmin)).get("status").asText()).isEqualTo("FAILED");
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '200 hours', "
                + "custom_domain_last_verified_at = now() - interval '71 hours' WHERE slug = 'acme'");
        assertThat(claim(bravo, bravoAdmin, domain).getStatus()).as("verified 71 h ago: still protected").isEqualTo(409);
        jdbc.update("UPDATE master.tenants SET custom_domain_last_verified_at = now() - interval '73 hours' WHERE slug = 'acme'");
        MockHttpServletResponse taken = claim(bravo, bravoAdmin, domain);
        assertThat(taken.getStatus()).as(taken.getContentAsString()).isEqualTo(200);
        assertThat(row("acme").get("custom_domain")).isNull();
        assertThat(jdbc.queryForObject("SELECT custom_domain_last_verified_at IS NULL FROM master.tenants WHERE slug = 'acme'",
                Boolean.class)).isTrue();
    }

    /** F1: replacing a VERIFIED domain starts a new claim; the old domain's verification does not protect the new one. */
    @Test
    void f1_aReplacementClaimDoesNotInheritTheOldDomainsVerification() throws Exception {
        claimAndVerify(acme, acmeAdmin, "shop.first-brand.com");
        assertThat(claim(acme, acmeAdmin, "shop.second-brand.com").getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT custom_domain_last_verified_at IS NULL FROM master.tenants WHERE slug = 'acme'",
                Boolean.class)).isTrue();
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '100 hours' WHERE slug = 'acme'");
        assertThat(claim(bravo, bravoAdmin, "shop.second-brand.com").getStatus()).isEqualTo(200);
    }

    /** F1: a squatter cannot keep its own unverified claim fresh with (failing) verify calls. */
    @Test
    void f1_failingVerifyCallsDoNotRefreshAnUnverifiedClaim() throws Exception {
        String domain = "shop.refresh-squat.com";
        assertThat(claim(bravo, bravoAdmin, domain).getStatus()).isEqualTo(200);
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '100 hours' WHERE slug = 'bravo'");
        assertThat(data(verifyNow(bravo, bravoAdmin)).get("status").asText()).isEqualTo("FAILED");
        assertThat(claim(acme, acmeAdmin, domain).getStatus()).isEqualTo(200);
        assertThat(row("bravo").get("custom_domain")).isNull();
    }

    /** L2 (review probe p8): the platform's own CNAME target is a platform name. */
    @Test
    void l2_theConfiguredCnameTargetAndItsParentAreNotClaimable() throws Exception {
        for (String d : List.of("stores.shopfi.app", "STORES.shopfi.app.", "x.stores.shopfi.app", "shopfi.app")) {
            MockHttpServletResponse r = claim(acme, acmeAdmin, d);
            assertThat(r.getStatus()).as(d).isEqualTo(400);
            assertThat(code(r)).as(d).isEqualTo("DOMAIN_PLATFORM");
        }
    }

    /** L7 / R01 (review probe p1): a re-claim with a new token while DNS is in flight is not verified by the old proof. */
    @Test
    void toctou_claimReplacedDuringTheDnsLookup_theOldProofNeverVerifiesTheNewClaim() throws Exception {
        String domain = "shop.toctou-one.com";
        JsonNode first = data(claim(acme, acmeAdmin, domain));
        String oldValue = first.get("txtRecordValue").asText();
        dns.put(first.get("txtRecordName").asText(), oldValue);
        String[] newValue = new String[1];
        dns.duringNextLookup(fqdn -> {
            service.remove("acme", SERVICE_ACTOR);
            newValue[0] = service.request("acme", domain, SERVICE_ACTOR).txtRecordValue();
        });
        MockHttpServletResponse v = verifyNow(acme, acmeAdmin);
        assertThat(v.getStatus()).isEqualTo(200);
        assertThat(newValue[0]).isNotEqualTo(oldValue);
        assertThat(data(v).get("status").asText()).isEqualTo("PENDING");
        assertThat(row("acme").get("custom_domain_status")).isEqualTo("PENDING");
        assertThat(preflight("https://" + domain)).isNull();
        assertThat(resolvedSlug("https://" + domain)).isEqualTo("UNKNOWN");
    }

    /** L7 / R02 (review probe p2): a stale claim released to another tenant during the lookup verifies nobody. */
    @Test
    void toctou_staleClaimReleasedToAnotherTenantDuringTheLookup_noTenantEndsUpVerified() throws Exception {
        String domain = "shop.toctou-two.com";
        JsonNode b = data(claim(bravo, bravoAdmin, domain));
        dns.put(b.get("txtRecordName").asText(), b.get("txtRecordValue").asText());
        jdbc.update("UPDATE master.tenants SET custom_domain_requested_at = now() - interval '100 hours' WHERE slug = 'bravo'");
        dns.duringNextLookup(fqdn -> service.request("acme", domain, SERVICE_ACTOR));
        MockHttpServletResponse r = verifyNow(bravo, bravoAdmin);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(data(r).get("domain").isNull()).isTrue();
        assertThat(row("bravo").get("custom_domain")).isNull();
        assertThat(row("acme").get("custom_domain_status")).isEqualTo("PENDING");
        assertThat(resolvedSlug("https://" + domain)).isEqualTo("UNKNOWN");
    }

    /** L7 / R05 (review probe p10): base-domain matching needs a dot boundary; platform domains never resolve. */
    @Test
    void hostRule_dotBoundaryAndPlatformDomains_evenForDirectDbRows() throws Exception {
        jdbc.update("UPDATE master.tenants SET custom_domain = 'evilshopfi.test', custom_domain_status = 'VERIFIED', "
                + "custom_domain_token = 'x' WHERE slug = 'acme'");
        jdbc.update("UPDATE master.tenants SET custom_domain = 'api.shopfi.example', custom_domain_status = 'VERIFIED', "
                + "custom_domain_token = 'y' WHERE slug = 'bravo'");
        originPolicy.invalidateAll();
        assertThat(resolvedSlug("https://evilshopfi.test")).as("not under shopfi.test: a separate domain").isEqualTo("acme");
        assertThat(preflight("https://evilshopfi.test")).isEqualTo("https://evilshopfi.test");
        assertThat(resolvedSlug("https://api.shopfi.example")).isEqualTo("UNKNOWN");
        assertThat(preflight("https://api.shopfi.example")).isNull();
    }

    /** L7 / R11 (review probe p11): claims and tenant-admin removals share one change budget; platform removal does not. */
    @Test
    void claimsAndTenantRemovalsShareTheChangeBudget_platformRemovalIsNotLimited() throws Exception {
        int first429 = -1;
        for (int i = 1; i <= 25 && first429 < 0; i++) {
            MockHttpServletResponse r = (i % 2 == 1) ? claim(acme, acmeAdmin, "shop.churn-brand.com") : removeNow(acme, acmeAdmin);
            if (r.getStatus() == 429) {
                first429 = i;
                assertThat(code(r)).isEqualTo("DOMAIN_CHANGE_RATE_LIMITED");
                assertThat(r.getHeader("Retry-After")).isNotBlank();
            }
        }
        // 10 claims + 10 removals = 20 changes; without the removals counting, the 21st call would still pass
        assertThat(first429).as("20 changes per hour shared by claims and removals").isEqualTo(21);
        assertThat(row("acme").get("custom_domain")).as("the refused claim changed nothing").isNull();
        jdbc.update("UPDATE master.tenants SET custom_domain = 'shop.churn-brand.com', custom_domain_status = 'PENDING', "
                + "custom_domain_token = 't', custom_domain_requested_at = now() WHERE slug = 'acme'");
        assertThat(removeNow(acme, acmeAdmin).getStatus()).as("tenant removal is limited too").isEqualTo(429);
        assertThat(row("acme").get("custom_domain")).isEqualTo("shop.churn-brand.com");
        service.remove("acme", new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, 1L, "root@x"));
        assertThat(row("acme").get("custom_domain")).as("platform removal is not limited").isNull();
    }

    /** L7 / R10: the global DNS budget (5/min in this context) applies across tenants and actors. */
    @Test
    void globalDnsBudgetIsSharedAcrossTenants() throws Exception {
        claim(acme, acmeAdmin, "shop.global-one.com");
        claim(bravo, bravoAdmin, "shop.global-two.com");
        Cookie platform = platformLogin();
        for (int i = 0; i < 3; i++) {
            assertThat(verifyNow(acme, acmeAdmin).getStatus()).isEqualTo(200);
        }
        assertThat(verifyNow(bravo, bravoAdmin).getStatus()).isEqualTo(200);
        assertThat(call(post("/api/v1/platform/tenants/bravo/custom-domain/verify").cookie(platform)).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = verifyNow(bravo, bravoAdmin); // bravo's own budget (3/h) still has room
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("DOMAIN_VERIFY_RATE_LIMITED");
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(dns.lookups()).isEqualTo(5);
    }

    // ---- L1 + review probes p9a/p9b: master V8 on legacy data (run inside a rolled-back transaction) -------------

    private String runV8(String schema, List<String> legacyValues) {
        String v8;
        try {
            v8 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migrations/master/V8__custom_domain_verification.sql"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '5s'");
                s.execute("CREATE SCHEMA " + schema);
                s.execute("SET LOCAL search_path TO " + schema);
                s.execute("CREATE TABLE tenants (id BIGINT PRIMARY KEY, slug VARCHAR(40) NOT NULL, custom_domain VARCHAR(255), "
                        + "updated_at TIMESTAMP, CONSTRAINT uq_tenants_custom_domain UNIQUE (custom_domain))");
                long id = 1;
                for (String v : legacyValues) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO tenants (id, slug, custom_domain) VALUES (?, ?, ?)")) {
                        ps.setLong(1, id);
                        ps.setString(2, "t" + id);
                        ps.setString(3, v);
                        ps.executeUpdate();
                    }
                    id++;
                }
                try {
                    s.execute(v8);
                } catch (java.sql.SQLException e) {
                    return "MIGRATION_FAILED: " + e.getMessage().lines().findFirst().orElse("");
                }
                StringBuilder out = new StringBuilder();
                try (java.sql.ResultSet rs = s.executeQuery("SELECT id, custom_domain, custom_domain_status, "
                        + "(custom_domain_token IS NOT NULL) FROM tenants ORDER BY id")) {
                    while (rs.next()) {
                        out.append(rs.getLong(1)).append('=').append(rs.getString(2)).append('/').append(rs.getString(3))
                                .append('/').append(rs.getBoolean(4)).append(' ');
                    }
                }
                return out.toString().strip();
            } finally {
                c.rollback(); // nothing persists (schema, table, function, triggers)
                c.setAutoCommit(auto);
            }
        });
    }

    @Test
    void v8_onLegacyData_normalisesClearsInvalidAndCaseClashes_andMarksTheRestPending() {
        String out = runV8("dom02_v8_a", java.util.Arrays.asList("Shop.Brand.COM", "shop.brand.com ", " Other.com. ",
                "acme.shopfi.test", "bücher.de", "\tTab.com", "a", null, "SHOP.BRAND.COM.", ".lead.com", "two..dots.com"));
        assertThat(out).isEqualTo("1=shop.brand.com/PENDING/true 2=null/null/false 3=other.com/PENDING/true "
                + "4=acme.shopfi.test/PENDING/true 5=null/null/false 6=null/null/false 7=a/PENDING/true 8=null/null/false "
                + "9=null/null/false 10=null/null/false 11=null/null/false");
    }

    @Test
    void v8_legacyValuesEndingInSeveralDots_areClearedAndDoNotBreakTheMigration() {
        String out = runV8("dom02_v8_b", List.of("ok-shop.com", "broken-shop.com..", "worse-shop.com...", "fine.com."));
        assertThat(out).isEqualTo("1=ok-shop.com/PENDING/true 2=null/null/false 3=null/null/false 4=fine.com/PENDING/true");
    }
}

package com.example.perfume_budget.platform;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.model.TenantAuditLog;
import com.example.perfume_budget.platform.repository.TenantAuditLogRepository;
import com.example.perfume_budget.platform.service.PlatformTenantService;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantSignup;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PLAT-02 on the REAL filter chain and PostgreSQL: security, listing, the state machine over HTTP, the immediate 423 and
 * CORS flip (default 30 s CORS cache TTL, no waiting), audit exactly once, races, and the real PROV-01 saga on reprovision.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://static.example.com",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        "platform.tenants.actions-per-minute=1000",
        "cors.tenant-cache.max-lookups-per-ip-per-second=1000",
        "cors.tenant-cache.max-lookups-per-second=1000",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0"
})
class PlatformTenantManagementIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PW = "Sup3r-Admin-pass!";
    static final String CONSOLE = "https://console.shopfi.example";
    static final String TENANT_PATH = "/api/v1/category/all";
    static final String BASE = "/api/v1/platform/tenants";
    static final AtomicInteger SEQ = new AtomicInteger();
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired PlatformAdminRepository admins;
    @Autowired TenantRepository tenantRepository;
    @Autowired TenantSignupRepository signupRepository;
    @Autowired TenantAuditLogRepository audits;
    @Autowired TenantProvisioningService provisioningService;
    @Autowired TenantOriginPolicy originPolicy;
    @Autowired PlatformTransactionManager txManager;
    @Autowired PasswordEncoder encoder;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @MockitoSpyBean TenantSchemaMigrator migrator;

    PlatformAdmin admin;
    Cookie adminCookie;

    @BeforeEach
    void login() throws Exception {
        tenants.ensureTenant("plat-a");
        tenants.ensureTenant("plat-b");
        setStatus("plat-a", TenantStatus.ACTIVE);
        setStatus("plat-b", TenantStatus.ACTIVE);
        for (String slug : List.of("plat-a", "plat-b")) {
            baseline.put(slug, scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug = ?", slug));
        }
        admin = newAdmin(true);
        adminCookie = loginCookie(admin);
    }

    @AfterEach
    void reset() {
        Mockito.reset(migrator);
        dropInjectedFailures();
        TenantContext.clear();
    }

    // ---- fixtures -----------------------------------------------------------------------------------------

    private PlatformAdmin newAdmin(boolean active) {
        return admins.save(PlatformAdmin.builder().email("p2root" + SEQ.incrementAndGet() + "@platform.test")
                .password(encoder.encode(PW)).fullName("Root").isActive(active).build());
    }

    private Cookie loginCookie(PlatformAdmin a) throws Exception {
        int n = SEQ.incrementAndGet();
        MockHttpServletResponse r = mvc.perform(post("/api/v1/platform/auth/login")
                        .with(req -> { req.setRemoteAddr("10.77." + (n / 250) + "." + (n % 250 + 1)); return req; })
                        .contentType("application/json")
                        .content("{\"email\":\"" + a.getEmail() + "\",\"password\":\"" + PW + "\"}"))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return new Cookie("platformAccessToken", r.getCookie("platformAccessToken").getValue());
    }

    private void setStatus(String slug, TenantStatus s) {
        Tenant t = tenantRepository.findBySlug(slug).orElseThrow();
        t.setStatus(s);
        tenantRepository.save(t);
    }

    /** A registry row plus (optionally) a verified onboarding signup holding only a BCrypt hash. */
    private Tenant mk(String slug, TenantStatus status, boolean verifiedSignup) {
        Tenant t = tenantRepository.saveAndFlush(Tenant.builder().slug(slug).displayName("Shop " + slug)
                .schemaName("tenant_" + slug.replace('-', '_')).status(status).plan(TenantPlan.FREE)
                .ownerEmail(slug + "@p2.test").build());
        LocalDateTime now = LocalDateTime.now();
        signupRepository.saveAndFlush(TenantSignup.builder().tenantId(t.getId()).ownerFullName("Owner " + slug)
                .passwordHash(encoder.encode("Own3r-Passw0rd!")).otpSalt("s").otpHash("h".repeat(64))
                .otpExpiresAt(now.minusMinutes(1)).failedAttempts(0).resendCount(0)
                .verifiedAt(verifiedSignup ? now : null).createdAt(now).build());
        return t;
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse act(String slug, String action) throws Exception {
        return call(post(BASE + "/" + slug + "/" + action).cookie(adminCookie).header("Origin", CONSOLE));
    }

    private MockHttpServletResponse preflight(String origin) throws Exception {
        return call(options(TENANT_PATH).header("Origin", origin).header("Access-Control-Request-Method", "GET"));
    }

    private static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString());
    }

    private static <T> T jdbc(SqlFn<T> fn) {
        try (Connection c = PostgresTestDatabase.connect()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    interface SqlFn<T> { T apply(Connection c) throws SQLException; }

    private static long scalar(String sql, String... args) {
        return jdbc(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** The audit table is append-only (cannot be cleaned), so shared fixtures are counted relative to a baseline. */
    private final java.util.Map<String, Long> baseline = new java.util.HashMap<>();

    private long auditRows(String slug) {
        return scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug = ?", slug) - baseline.getOrDefault(slug, 0L);
    }

    private String auditLine(String slug, int rel) {
        int index = rel + baseline.getOrDefault(slug, 0L).intValue();
        return jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT actor_admin_id, actor_email, action, previous_status, new_status, occurred_at "
                    + "FROM master.tenant_audit_log WHERE tenant_slug = ? ORDER BY id OFFSET ? LIMIT 1")) {
                ps.setString(1, slug);
                ps.setInt(2, index);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getTimestamp(6)).isNotNull();
                    return rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5);
                }
            }
        });
    }

    private String phases(String slug) {
        return jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT phase FROM master.tenant_audit_log WHERE tenant_slug = ? ORDER BY id OFFSET ?")) {
                ps.setString(1, slug);
                ps.setInt(2, baseline.getOrDefault(slug, 0L).intValue());
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                    return String.join(",", out);
                }
            }
        });
    }

    private static String status(String slug) {
        return jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT status FROM master.tenants WHERE slug = ?")) {
                ps.setString(1, slug);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // ---- security: SUPER_ADMIN guard and token-type separation -----------------------------------------------

    private List<MockHttpServletRequestBuilder> allEndpoints() {
        return List.of(get(BASE), post(BASE + "/plat-a/suspend"), post(BASE + "/plat-a/reactivate"),
                post(BASE + "/plat-a/approve"), post(BASE + "/plat-a/reprovision"));
    }

    @Test
    void anonymous_isRejected_onEveryEndpoint_andNothingChanges() throws Exception {
        for (MockHttpServletRequestBuilder b : allEndpoints()) {
            assertThat(call(b).getStatus()).isIn(401, 403);
        }
        assertThat(call(post(BASE + "/plat-a/suspend")).getStatus()).isEqualTo(401);
        assertThat(status("plat-a")).isEqualTo("ACTIVE");
        assertThat(auditRows("plat-a")).isZero();
    }

    @Test
    void tenantAccessToken_andTenantRefreshToken_areRejected_inEveryCookieShape() throws Exception {
        TestTenant a = tenants.ensureTenant("plat-a");
        String access = tenants.runAs(a, () -> jwtUtil.generateAccessToken(a.adminEmail(), "ADMIN", 1L, "Admin"));
        String refresh = tenants.runAs(a, () -> jwtUtil.generateRefreshToken(a.adminEmail(), "ADMIN", 1L, "Admin"));
        // worst case: a tenant user whose email equals a real platform admin's email
        String sameEmailAccess = tenants.runAs(a, () -> jwtUtil.generateAccessToken(admin.getEmail(), "ADMIN", 1L, "Admin"));
        String sameEmailRefresh = tenants.runAs(a, () -> jwtUtil.generateRefreshToken(admin.getEmail(), "ADMIN", 1L, "Admin"));
        List<Cookie> forged = List.of(new Cookie("platformAccessToken", sameEmailAccess),
                new Cookie("platformAccessToken", sameEmailRefresh), new Cookie("accessToken", sameEmailAccess),
                new Cookie("accessToken", access), new Cookie("refreshToken", refresh),
                new Cookie("accessToken", refresh), new Cookie("platformAccessToken", access),
                new Cookie("platformAccessToken", refresh), new Cookie("platformRefreshToken", refresh));
        for (Cookie c : forged) {
            for (MockHttpServletRequestBuilder b : allEndpoints()) {
                int s = call(b.cookie(c).header("Authorization", "Bearer " + access)).getStatus();
                assertThat(s).as(c.getName() + " " + b).isIn(401, 403);
            }
            // also when the request claims to come from the tenant's own host
            int s = call(post(BASE + "/plat-b/suspend").cookie(c).header("Origin", "https://plat-a.shopfi.test")).getStatus();
            assertThat(s).isIn(401, 403);
        }
        assertThat(status("plat-a")).isEqualTo("ACTIVE");
        assertThat(status("plat-b")).isEqualTo("ACTIVE");
        assertThat(auditRows("plat-b")).isZero();
    }

    @Test
    void validPlatformSession_worksOnlyFromThePlatformOrigin_notFromATenantOrigin() throws Exception {
        MockHttpServletResponse viaTenantOrigin = call(post(BASE + "/plat-a/suspend").cookie(adminCookie)
                .header("Origin", "https://plat-b.shopfi.test"));
        assertThat(viaTenantOrigin.getStatus()).isEqualTo(403);
        assertThat(viaTenantOrigin.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(call(post(BASE + "/plat-a/suspend").cookie(adminCookie).header("Origin", "https://evil.example")).getStatus()).isEqualTo(403);
        assertThat(status("plat-a")).isEqualTo("ACTIVE");
        assertThat(auditRows("plat-a")).isZero();
    }

    @Test
    void deactivatedAdmin_losesAccessOnTheNextRequest() throws Exception {
        PlatformAdmin gone = newAdmin(true);
        Cookie c = loginCookie(gone);
        assertThat(call(get(BASE).cookie(c)).getStatus()).isEqualTo(200);
        gone.setActive(false);
        admins.save(gone);
        assertThat(call(get(BASE).cookie(c)).getStatus()).isEqualTo(401);
        assertThat(call(post(BASE + "/plat-a/suspend").cookie(c)).getStatus()).isEqualTo(401);
        assertThat(status("plat-a")).isEqualTo("ACTIVE");
    }

    @Test
    void noWriteVerbsExistForTenantsOrTheAuditLog() throws Exception {
        for (MockHttpServletRequestBuilder b : List.of(put(BASE + "/plat-a"), patch(BASE + "/plat-a"), delete(BASE + "/plat-a"),
                delete(BASE + "/plat-a/audit"), put(BASE + "/audit"), delete(BASE), post(BASE),
                get(BASE + "/plat-a/audit"), get("/api/v1/platform/audit"))) {
            int s = call(b.cookie(adminCookie)).getStatus();
            assertThat(s).as(b.toString()).isBetween(400, 499);
        }
        assertThat(status("plat-a")).isEqualTo("ACTIVE");
    }

    // ---- list ---------------------------------------------------------------------------------------------

    @Test
    void list_returnsOnlyRegistryFields_neverSchemaWebhookOrBusinessData() throws Exception {
        Tenant t = tenantRepository.findBySlug("plat-a").orElseThrow();
        t.setWebhookToken("WEBHOOK-SECRET-TOKEN-123");
        tenantRepository.save(t);
        MockHttpServletResponse r = call(get(BASE + "?size=100").cookie(adminCookie));
        assertThat(r.getStatus()).isEqualTo(200);
        String raw = r.getContentAsString();
        assertThat(raw).doesNotContain("WEBHOOK-SECRET").doesNotContain("tenant_plat_a").doesNotContain("schemaName")
                .doesNotContain("webhookToken").doesNotContain("password").doesNotContain("$2a$");
        Set<String> allowed = Set.of("id", "slug", "displayName", "status", "plan", "ownerEmail", "customDomain", "createdAt", "updatedAt");
        for (JsonNode item : body(r).get("data").get("items")) {
            Set<String> keys = new HashSet<>();
            item.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).isEqualTo(allowed);
        }
    }

    @Test
    void list_statusFilter_pageSizeCap_andStableDeterministicPaging() throws Exception {
        for (int i = 0; i < 4; i++) {
            mk("p2-list-" + i, TenantStatus.FAILED, false);
        }
        JsonNode failed = body(call(get(BASE + "?status=failed&size=1000&sort=slug&direction=asc").cookie(adminCookie))).get("data");
        assertThat(failed.get("size").asInt()).isEqualTo(100);
        assertThat(failed.get("sort").asText()).isEqualTo("slug");
        for (JsonNode i : failed.get("items")) {
            assertThat(i.get("status").asText()).isEqualTo("FAILED");
        }
        // page through one by one: no duplicates, no gaps, order equals the one-shot ordering
        List<String> oneShot = new ArrayList<>();
        failed.get("items").forEach(n -> oneShot.add(n.get("slug").asText()));
        assertThat(oneShot).isSorted();
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < oneShot.size(); p++) {
            JsonNode d = body(call(get(BASE + "?status=FAILED&size=1&page=" + p + "&sort=slug&direction=asc").cookie(adminCookie))).get("data");
            assertThat(d.get("totalElements").asInt()).isEqualTo(oneShot.size());
            paged.add(d.get("items").get(0).get("slug").asText());
        }
        assertThat(paged).isEqualTo(oneShot);
        // default sort is deterministic across repeated calls
        String first = call(get(BASE + "?size=100").cookie(adminCookie)).getContentAsString();
        assertThat(call(get(BASE + "?size=100").cookie(adminCookie)).getContentAsString()).isEqualTo(first);
    }

    @Test
    void list_badParameters_are400() throws Exception {
        for (String q : List.of("?status=nope", "?page=-1", "?size=0", "?size=abc", "?sort=ownerEmail;drop", "?direction=up",
                "?sort=schemaName", "?status=ACTIVE'%20OR%201=1")) {
            assertThat(call(get(BASE + q).cookie(adminCookie)).getStatus()).as(q).isEqualTo(400);
        }
    }

    // ---- suspend / reactivate: immediate 423 and immediate CORS flip, audit exactly once -------------------------

    @Test
    void suspend_flipsTenantApiAndCorsImmediately_notAfterTheTtl_andReactivateFlipsBack() throws Exception {
        String origin = "https://plat-a.shopfi.test";
        String other = "https://plat-b.shopfi.test";
        // warm every cache (allowed decisions live 30 s by default)
        assertThat(preflight(origin).getStatus()).isEqualTo(200);
        assertThat(call(get(TENANT_PATH).header("Origin", origin)).getStatus()).isEqualTo(200);
        assertThat(call(get(TENANT_PATH).header("Origin", other)).getStatus()).isEqualTo(200);

        MockHttpServletResponse s = act("plat-a", "suspend");
        assertThat(s.getStatus()).isEqualTo(200);
        assertThat(body(s).get("data").get("changed").asBoolean()).isTrue();
        assertThat(body(s).get("data").get("tenant").get("status").asText()).isEqualTo("SUSPENDED");

        // the very next requests, no sleeping
        MockHttpServletResponse pre = preflight(origin);
        assertThat(pre.getStatus()).isEqualTo(403);
        assertThat(pre.getHeader("Access-Control-Allow-Origin")).isNull();
        MockHttpServletResponse withOrigin = call(get(TENANT_PATH).header("Origin", origin));
        assertThat(withOrigin.getStatus()).isEqualTo(403);
        assertThat(withOrigin.getHeader("Access-Control-Allow-Origin")).isNull();
        // tenant resolution (Referer carries the tenant signal, no CORS gate): 423 on the next request
        assertThat(call(get(TENANT_PATH).header("Referer", origin + "/")).getStatus()).isEqualTo(423);
        assertThat(call(get(TENANT_PATH).header("Referer", origin + "/").cookie(new Cookie("accessToken", "x"))).getStatus()).isEqualTo(423);
        // isolation: the other tenant is untouched
        assertThat(call(get(TENANT_PATH).header("Origin", other)).getStatus()).isEqualTo(200);
        assertThat(preflight(other).getStatus()).isEqualTo(200);
        assertThat(call(get(TENANT_PATH).header("Referer", other + "/")).getStatus()).isEqualTo(200);

        MockHttpServletResponse re = act("plat-a", "reactivate");
        assertThat(re.getStatus()).isEqualTo(200);
        assertThat(body(re).get("data").get("tenant").get("status").asText()).isEqualTo("ACTIVE");
        // the 10 s negative CORS cache must not delay the reactivation either
        assertThat(preflight(origin).getStatus()).isEqualTo(200);
        assertThat(call(get(TENANT_PATH).header("Origin", origin)).getStatus()).isEqualTo(200);
        assertThat(call(get(TENANT_PATH).header("Referer", origin + "/")).getStatus()).isEqualTo(200);

        assertThat(auditRows("plat-a")).isEqualTo(2);
        assertThat(auditLine("plat-a", 0)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|SUSPEND|ACTIVE|SUSPENDED");
        assertThat(auditLine("plat-a", 1)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|REACTIVATE|SUSPENDED|ACTIVE");
        assertThat(auditRows("plat-b")).isZero();
    }

    @Test
    void idempotentRepeats_writeNoExtraAuditRows_andIllegalOrUnknownWriteNone() throws Exception {
        assertThat(act("plat-b", "suspend").getStatus()).isEqualTo(200);
        MockHttpServletResponse again = act("plat-b", "suspend");
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(body(again).get("data").get("changed").asBoolean()).isFalse();
        assertThat(auditRows("plat-b")).isEqualTo(1);
        assertThat(act("plat-b", "reactivate").getStatus()).isEqualTo(200);
        MockHttpServletResponse noop = act("plat-b", "reactivate");
        assertThat(body(noop).get("data").get("changed").asBoolean()).isFalse();
        assertThat(act("plat-b", "approve").getStatus()).isEqualTo(200); // ACTIVE: idempotent
        assertThat(auditRows("plat-b")).isEqualTo(2);

        mk("p2-illegal", TenantStatus.FAILED, true);
        mk("p2-illegal-sus", TenantStatus.SUSPENDED, true);
        for (String[] c : new String[][]{{"p2-illegal", "suspend"}, {"p2-illegal", "reactivate"}, {"p2-illegal", "approve"},
                {"p2-illegal-sus", "approve"}, {"p2-illegal-sus", "reprovision"}, {"plat-b", "reprovision"}}) {
            MockHttpServletResponse r = act(c[0], c[1]);
            assertThat(r.getStatus()).as(c[0] + " " + c[1]).isEqualTo(409);
            assertThat(body(r).get("data").isNull()).isTrue();
            assertThat(r.getContentAsString()).doesNotContain("$2a$").doesNotContain("Exception");
        }
        assertThat(status("p2-illegal")).isEqualTo("FAILED");
        assertThat(auditRows("p2-illegal") + auditRows("p2-illegal-sus")).isZero();
        assertThat(auditRows("plat-b")).isEqualTo(2);
    }

    @Test
    void unknownAndMalformedSlugs_are404_withIdenticalBodies() throws Exception {
        String reference = null;
        for (String slug : List.of("nosuchshop", "no-such-shop-either", "a", "UPPER", "x'%3B--", "..%2Fmaster", "tenant_template", "a".repeat(80))) {
            for (String action : List.of("suspend", "reactivate", "approve", "reprovision")) {
                MockHttpServletResponse r = act(slug, action);
                assertThat(r.getStatus()).as(slug + " " + action).isIn(404, 400);
                if (r.getStatus() == 404) {
                    if (reference == null) {
                        reference = r.getContentAsString();
                    }
                    assertThat(r.getContentAsString()).isEqualTo(reference);
                }
            }
        }
        assertThat(reference).isNotNull().doesNotContain("nosuchshop");
    }

    @Test
    void platformCallsLeaveNoTenantContextBehind() throws Exception {
        act("plat-b", "suspend");
        act("plat-b", "reactivate");
        call(get(BASE).cookie(adminCookie));
        assertThat(TenantContext.get()).isNull();
    }

    // ---- reprovision / approve: the real PROV-01 saga ------------------------------------------------------------

    @Test
    void reprovision_failedTenant_runsTheRealSaga_audits_dropsHash_andEnablesCorsImmediately() throws Exception {
        mk("p2-rp-ok", TenantStatus.FAILED, true);
        String origin = "https://p2-rp-ok.shopfi.test";
        assertThat(preflight(origin).getStatus()).isEqualTo(403); // warm the negative CORS cache
        MockHttpServletResponse r = act("p2-rp-ok", "reprovision");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("changed").asBoolean()).isTrue();
        assertThat(d.get("previousStatus").asText()).isEqualTo("FAILED");
        assertThat(d.get("tenant").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(r.getContentAsString()).doesNotContain("$2a$").doesNotContain("Own3r");
        assertThat(status("p2-rp-ok")).isEqualTo("ACTIVE");
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'tenant_p2_rp_ok'")).isGreaterThan(5);
        assertThat(scalar("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id = s.tenant_id WHERE t.slug = ?",
                "p2-rp-ok")).isZero();
        assertThat(auditRows("p2-rp-ok")).isEqualTo(2);
        assertThat(phases("p2-rp-ok")).isEqualTo("INTENT,OUTCOME");
        assertThat(auditLine("p2-rp-ok", 0)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|REPROVISION|FAILED|PROVISIONING");
        assertThat(auditLine("p2-rp-ok", 1)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|REPROVISION|FAILED|ACTIVE");
        assertThat(preflight(origin).getStatus()).isEqualTo(200); // flipped at once, not after the 10 s negative TTL
        // a second call is now illegal (ACTIVE tenants are never reprovisioned) and adds no audit row
        assertThat(act("p2-rp-ok", "reprovision").getStatus()).isEqualTo(409);
        assertThat(auditRows("p2-rp-ok")).isEqualTo(2);
    }

    @Test
    void reprovision_sagaFailure_is500_generic_marksFailed_auditsOnce_andALaterRetrySucceeds() throws Exception {
        mk("p2-rp-fail", TenantStatus.FAILED, true);
        doThrow(new IllegalStateException("jdbc:postgresql://db.internal password=hunter2")).when(migrator).migrateTenant("tenant_p2_rp_fail");
        MockHttpServletResponse r = act("p2-rp-fail", "reprovision");
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentAsString()).doesNotContain("hunter2").doesNotContain("db.internal").doesNotContain("$2a$");
        assertThat(status("p2-rp-fail")).isEqualTo("FAILED");
        assertThat(scalar("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'tenant_p2_rp_fail'")).isZero();
        assertThat(auditRows("p2-rp-fail")).isEqualTo(2);
        assertThat(phases("p2-rp-fail")).isEqualTo("INTENT,OUTCOME");
        assertThat(auditLine("p2-rp-fail", 0)).endsWith("|REPROVISION|FAILED|PROVISIONING");
        assertThat(auditLine("p2-rp-fail", 1)).endsWith("|REPROVISION|FAILED|FAILED");
        assertThat(scalar("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id = s.tenant_id WHERE t.slug = ?",
                "p2-rp-fail")).isEqualTo(1); // credential kept for the retry

        Mockito.reset(migrator);
        assertThat(act("p2-rp-fail", "reprovision").getStatus()).isEqualTo(200);
        assertThat(status("p2-rp-fail")).isEqualTo("ACTIVE");
        assertThat(auditRows("p2-rp-fail")).isEqualTo(4);
        assertThat(phases("p2-rp-fail")).isEqualTo("INTENT,OUTCOME,INTENT,OUTCOME");
    }

    @Test
    void approve_verifiedPending_provisions_unverifiedPending_is409_andCreatesNothing() throws Exception {
        mk("p2-ap-ok", TenantStatus.PENDING, true);
        assertThat(act("p2-ap-ok", "approve").getStatus()).isEqualTo(200);
        assertThat(status("p2-ap-ok")).isEqualTo("ACTIVE");
        assertThat(phases("p2-ap-ok")).isEqualTo("INTENT,OUTCOME");
        assertThat(auditLine("p2-ap-ok", 0)).endsWith("|APPROVE|PENDING|PROVISIONING");
        assertThat(auditLine("p2-ap-ok", 1)).endsWith("|APPROVE|PENDING|ACTIVE");

        mk("p2-ap-unv", TenantStatus.PENDING, false);
        MockHttpServletResponse r = act("p2-ap-unv", "approve");
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(status("p2-ap-unv")).isEqualTo("PENDING");
        assertThat(scalar("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'tenant_p2_ap_unv'")).isZero();
        assertThat(auditRows("p2-ap-unv")).isZero();
    }

    @Test
    void reprovision_stuckProvisioning_isReclaimedAndProvisioned_butAFreshOneIsLeftAlone() throws Exception {
        mk("p2-stuck-fresh", TenantStatus.PROVISIONING, true);
        MockHttpServletResponse fresh = act("p2-stuck-fresh", "reprovision");
        assertThat(fresh.getStatus()).isEqualTo(202);
        assertThat(body(fresh).get("data").get("inProgress").asBoolean()).isTrue();
        assertThat(auditRows("p2-stuck-fresh")).isZero();

        Tenant t = mk("p2-stuck-old", TenantStatus.PROVISIONING, true);
        jdbc(c -> {
            try (var ps = c.prepareStatement("UPDATE master.tenants SET updated_at = now() - interval '3 hours' WHERE id = ?")) {
                ps.setLong(1, t.getId());
                return ps.executeUpdate();
            }
        });
        assertThat(act("p2-stuck-old", "reprovision").getStatus()).isEqualTo(200);
        assertThat(status("p2-stuck-old")).isEqualTo("ACTIVE");
        assertThat(phases("p2-stuck-old")).isEqualTo("RECLAIM,INTENT,OUTCOME");
        assertThat(auditLine("p2-stuck-old", 0)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|REPROVISION|PROVISIONING|FAILED");
        assertThat(auditLine("p2-stuck-old", 1)).endsWith("|REPROVISION|FAILED|PROVISIONING");
        assertThat(auditLine("p2-stuck-old", 2)).endsWith("|REPROVISION|FAILED|ACTIVE");
    }

    // ---- PLAT-02b: audit integrity around the saga ---------------------------------------------------------------

    private final List<String> injected = new ArrayList<>();

    /** Real database fault injection: an audit insert of this slug+phase raises inside PostgreSQL (rolled back, class-only log). */
    private void failAuditWrites(String slug, TenantAuditPhase phase) {
        String name = "p2b_fail_audit_" + SEQ.incrementAndGet();
        jdbc(c -> {
            try (var st = c.createStatement()) {
                st.execute("CREATE OR REPLACE FUNCTION master." + name + "() RETURNS trigger AS $f$ BEGIN "
                        + "IF NEW.tenant_slug = '" + slug + "' AND NEW.phase = '" + phase.name() + "' THEN "
                        + "RAISE EXCEPTION 'injected jdbc:postgresql://db.internal password=hunter2'; END IF; RETURN NEW; END; $f$ LANGUAGE plpgsql");
                st.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON master.tenant_audit_log FOR EACH ROW EXECUTE FUNCTION master." + name + "()");
            }
            return null;
        });
        injected.add("master.tenant_audit_log:" + name);
    }

    /** A delete of this tenant's signup row (the BCrypt hash) fails inside PostgreSQL until the fault is dropped. */
    private void failSignupDeletes(String slug) {
        String name = "p2b_fail_signup_" + SEQ.incrementAndGet();
        jdbc(c -> {
            try (var st = c.createStatement()) {
                st.execute("CREATE OR REPLACE FUNCTION master." + name + "() RETURNS trigger AS $f$ BEGIN "
                        + "IF OLD.tenant_id = (SELECT id FROM master.tenants WHERE slug = '" + slug + "') THEN "
                        + "RAISE EXCEPTION 'injected delete failure'; END IF; RETURN OLD; END; $f$ LANGUAGE plpgsql");
                st.execute("CREATE TRIGGER " + name + " BEFORE DELETE ON master.tenant_signups FOR EACH ROW EXECUTE FUNCTION master." + name + "()");
            }
            return null;
        });
        injected.add("master.tenant_signups:" + name);
    }

    private void dropInjectedFailures() {
        for (String e : injected) {
            String table = e.substring(0, e.indexOf(':'));
            String name = e.substring(e.indexOf(':') + 1);
            jdbc(c -> {
                try (var st = c.createStatement()) {
                    st.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
                    st.execute("DROP FUNCTION IF EXISTS master." + name + "()");
                }
                return null;
            });
        }
        injected.clear();
    }

    private long signupRows(String slug) {
        return scalar("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id = s.tenant_id WHERE t.slug = ?", slug);
    }

    /** A service instance with no reconcile grace (the HTTP one keeps the 60 s default so it never races a live winner). */
    private PlatformTenantService noGraceService() {
        return new PlatformTenantService(tenantRepository, signupRepository, audits, provisioningService, originPolicy,
                txManager, 1000, 15, 0);
    }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal(admin.getId(), admin.getEmail(), "Root");
    }

    @Test
    void crashBetweenSagaCommitAndOutcomeWrite_leavesIntent_tenantActive_hashGone_andARetryReconcilesOnce() throws Exception {
        mk("p2-crash", TenantStatus.PENDING, true);
        failAuditWrites("p2-crash", TenantAuditPhase.OUTCOME);
        MockHttpServletResponse r = act("p2-crash", "approve");
        // the saga succeeded: the admin is told so even though the outcome row could not be written
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentAsString()).doesNotContain("hunter2").doesNotContain("db.internal");
        assertThat(status("p2-crash")).isEqualTo("ACTIVE");
        assertThat(phases("p2-crash")).isEqualTo("INTENT");                     // intent survived, outcome lost
        assertThat(auditLine("p2-crash", 0)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|APPROVE|PENDING|PROVISIONING");
        assertThat(signupRows("p2-crash")).isZero();                            // hash delete is independent of the audit

        dropInjectedFailures();
        // within the grace period an in-flight winner is presumed: the HTTP retry is a plain no-op
        MockHttpServletResponse early = act("p2-crash", "approve");
        assertThat(early.getStatus()).isEqualTo(200);
        assertThat(phases("p2-crash")).isEqualTo("INTENT");

        var retry = noGraceService().approve("p2-crash", principal());
        assertThat(retry.changed()).isFalse();
        assertThat(phases("p2-crash")).isEqualTo("INTENT,RECONCILE");
        assertThat(auditLine("p2-crash", 1)).isEqualTo(admin.getId() + "|" + admin.getEmail() + "|APPROVE|ACTIVE|ACTIVE");
        noGraceService().approve("p2-crash", principal());                       // reconciled once, then quiet
        assertThat(phases("p2-crash")).isEqualTo("INTENT,RECONCILE");
    }

    @Test
    void hashDeleteFailingAfterTheSaga_keepsTenantActiveAndOutcome_andARetryDeletesTheHashAndAudits() throws Exception {
        mk("p2-hash", TenantStatus.PENDING, true);
        failSignupDeletes("p2-hash");
        assertThat(act("p2-hash", "approve").getStatus()).isEqualTo(200);
        assertThat(status("p2-hash")).isEqualTo("ACTIVE");
        assertThat(phases("p2-hash")).isEqualTo("INTENT,OUTCOME");              // the audit row does not depend on the delete
        assertThat(signupRows("p2-hash")).isEqualTo(1);                         // the hash is still there

        dropInjectedFailures();
        var retry = noGraceService().approve("p2-hash", principal());
        assertThat(retry.changed()).isFalse();
        assertThat(signupRows("p2-hash")).isZero();
        assertThat(phases("p2-hash")).isEqualTo("INTENT,OUTCOME,RECONCILE");
        noGraceService().approve("p2-hash", principal());
        assertThat(phases("p2-hash")).isEqualTo("INTENT,OUTCOME,RECONCILE");    // nothing left to repair: nothing written
    }

    @Test
    void failureBranch_auditWriteThrowing_neverReplacesTheGeneric500() throws Exception {
        mk("p2-fb", TenantStatus.FAILED, true);
        doThrow(new IllegalStateException("jdbc:postgresql://db.internal password=hunter2")).when(migrator).migrateTenant("tenant_p2_fb");
        failAuditWrites("p2-fb", TenantAuditPhase.OUTCOME);
        MockHttpServletResponse r = act("p2-fb", "reprovision");
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(body(r).get("description").asText()).isEqualTo("Provisioning failed for tenant 'p2-fb'; it is now FAILED and can be reprovisioned");
        assertThat(r.getContentAsString()).doesNotContain("hunter2").doesNotContain("db.internal").doesNotContain("injected");
        assertThat(status("p2-fb")).isEqualTo("FAILED");
        assertThat(phases("p2-fb")).isEqualTo("INTENT");
        assertThat(signupRows("p2-fb")).isEqualTo(1);
    }

    @Test
    void intentWriteFailing_rollsBackTheClaim_nothingIsProvisioned_andARetryWorks() throws Exception {
        mk("p2-intent", TenantStatus.FAILED, true);
        failAuditWrites("p2-intent", TenantAuditPhase.INTENT);
        MockHttpServletResponse r = act("p2-intent", "reprovision");
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentAsString()).doesNotContain("hunter2").doesNotContain("db.internal");
        assertThat(status("p2-intent")).isEqualTo("FAILED");
        verify(migrator, never()).createTenantSchema("tenant_p2_intent");
        assertThat(auditRows("p2-intent")).isZero();
        assertThat(signupRows("p2-intent")).isEqualTo(1);
        dropInjectedFailures();
        assertThat(act("p2-intent", "reprovision").getStatus()).isEqualTo(200);
        assertThat(phases("p2-intent")).isEqualTo("INTENT,OUTCOME");
    }

    @Test
    void intentRowIsCommittedBeforeTheSagaCreatesAnything() throws Exception {
        mk("p2-order", TenantStatus.PENDING, true);
        java.util.concurrent.atomic.AtomicReference<String> seenDuringSaga = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            seenDuringSaga.set(phases("p2-order") + "/" + status("p2-order")); // from a separate connection: committed rows only
            return inv.callRealMethod();
        }).when(migrator).createTenantSchema("tenant_p2_order");
        assertThat(act("p2-order", "approve").getStatus()).isEqualTo(200);
        assertThat(seenDuringSaga.get()).isEqualTo("INTENT/PROVISIONING");
    }

    @Test
    void suspendAndReactivate_stayOneOutcomeRow_each() throws Exception {
        act("plat-a", "suspend");
        act("plat-a", "reactivate");
        assertThat(phases("plat-a")).isEqualTo("OUTCOME,OUTCOME");
    }

    // ---- concurrency ---------------------------------------------------------------------------------------------

    private List<MockHttpServletResponse> race(int n, Callable<MockHttpServletResponse> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<MockHttpServletResponse> out = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : fs) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    private static long changed(List<MockHttpServletResponse> rs) throws Exception {
        long n = 0;
        for (MockHttpServletResponse r : rs) {
            if (r.getStatus() == 200 && body(r).get("data").get("changed").asBoolean()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void concurrentReprovision_provisionsOnce_auditsOnce_noServerErrors() throws Exception {
        mk("p2-race-rp", TenantStatus.FAILED, true);
        doAnswer(inv -> {
            Thread.sleep(700); // keep the saga PROVISIONING while the second caller arrives
            return inv.callRealMethod();
        }).when(migrator).migrateTenant("tenant_p2_race_rp");
        List<MockHttpServletResponse> rs = race(3, () -> act("p2-race-rp", "reprovision"));
        for (MockHttpServletResponse r : rs) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 202, 409);
        }
        assertThat(changed(rs)).isEqualTo(1);
        verify(migrator, times(1)).migrateTenant("tenant_p2_race_rp");
        assertThat(status("p2-race-rp")).isEqualTo("ACTIVE");
        assertThat(auditRows("p2-race-rp")).isEqualTo(2);
        assertThat(phases("p2-race-rp")).isEqualTo("INTENT,OUTCOME"); // a loser never writes a second intent
        assertThat(tenantRepository.findBySlug("p2-race-rp").orElseThrow().getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void concurrentApprove_provisionsOnce_auditsOnce() throws Exception {
        mk("p2-race-ap", TenantStatus.PENDING, true);
        doAnswer(inv -> {
            Thread.sleep(700);
            return inv.callRealMethod();
        }).when(migrator).migrateTenant("tenant_p2_race_ap");
        List<MockHttpServletResponse> rs = race(3, () -> act("p2-race-ap", "approve"));
        for (MockHttpServletResponse r : rs) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 202, 409);
        }
        assertThat(changed(rs)).isEqualTo(1);
        verify(migrator, times(1)).migrateTenant("tenant_p2_race_ap");
        assertThat(auditRows("p2-race-ap")).isEqualTo(2);
        assertThat(phases("p2-race-ap")).isEqualTo("INTENT,OUTCOME");
        assertThat(status("p2-race-ap")).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentSuspend_andSuspendVsReactivate_neverDoubleAudit_andEndInALegalState() throws Exception {
        List<MockHttpServletResponse> rs = race(6, () -> act("plat-b", "suspend"));
        assertThat(changed(rs)).isEqualTo(1);
        assertThat(auditRows("plat-b")).isEqualTo(1);
        assertThat(status("plat-b")).isEqualTo("SUSPENDED");

        AtomicInteger i = new AtomicInteger();
        List<MockHttpServletResponse> mixed = race(6, () -> act("plat-b", i.getAndIncrement() % 2 == 0 ? "reactivate" : "suspend"));
        for (MockHttpServletResponse r : mixed) {
            assertThat(r.getStatus()).isIn(200);
        }
        long rows = auditRows("plat-b");
        assertThat(rows).isEqualTo(1 + changed(mixed));
        assertThat(status("plat-b")).isIn("ACTIVE", "SUSPENDED");
        // chain of audit rows is consistent: each row's previous status is the prior row's new status
        String prevNew = null;
        for (int k = 0; k < rows; k++) {
            String[] f = auditLine("plat-b", k).split("\\|");
            if (prevNew != null) {
                assertThat(f[3]).isEqualTo(prevNew);
            }
            prevNew = f[4];
        }
        assertThat(prevNew).isEqualTo(status("plat-b"));
    }

    // ---- audit tamper resistance ---------------------------------------------------------------------------------

    @Test
    void auditLog_rejectsUpdateDeleteAndTruncate_atTheDatabase() throws Exception {
        act("plat-b", "suspend");
        act("plat-b", "reactivate");
        long before = scalar("SELECT count(*) FROM master.tenant_audit_log");
        assertThat(before).isGreaterThanOrEqualTo(2);
        for (String sql : List.of("UPDATE master.tenant_audit_log SET actor_email = 'x@y.z'",
                "DELETE FROM master.tenant_audit_log", "DELETE FROM master.tenant_audit_log WHERE tenant_slug = 'plat-b'",
                "TRUNCATE master.tenant_audit_log")) {
            assertThatThrownBy(() -> jdbc(c -> {
                try (var st = c.createStatement()) {
                    return st.executeUpdate(sql);
                }
            })).as(sql).hasMessageContaining("append-only");
        }
        assertThat(scalar("SELECT count(*) FROM master.tenant_audit_log")).isEqualTo(before);
        // deleting the tenant registry row does not touch the history (no foreign key)
        assertThat(scalar("SELECT count(*) FROM pg_constraint WHERE conrelid = 'master.tenant_audit_log'::regclass AND contype = 'f'")).isZero();
    }

    @Test
    void auditRows_carryNoSecrets_onlyIdentifiersAndStatuses() throws Exception {
        List<String> columns = jdbc(c -> {
            List<String> cols = new ArrayList<>();
            try (var ps = c.prepareStatement("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'master' AND table_name = 'tenant_audit_log'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
            return cols;
        });
        assertThat(columns).containsExactlyInAnyOrder("id", "occurred_at", "actor_admin_id", "actor_email", "tenant_id", "tenant_slug",
                "action", "previous_status", "new_status", "phase");
    }
}

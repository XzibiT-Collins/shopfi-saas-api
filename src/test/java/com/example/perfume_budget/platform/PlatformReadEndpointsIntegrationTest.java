package com.example.perfume_budget.platform;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.controller.PlatformTenantController;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantSignup;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.platform.repository.TenantAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.platform.service.PlatformTenantService;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import org.hibernate.SessionFactory;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PLAT-04 on the REAL filter chain and PostgreSQL: tenant detail, audit-log read and list search for the platform
 * console. Audit rows are produced by the real service flows (saga, reclaim, reconcile, suspend/reactivate).
 */
@AutoConfigureMockMvc
// Kept on purpose: the extra hibernate.generate_statistics property gives this class its OWN cached context (and Hikari
// pool, 6 connections). Closing it after the class frees those Postgres connection slots for the following test classes
// (leaked contexts exhaust the slots and make later tests skip).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://static.example.com",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        "platform.tenants.actions-per-minute=1000",
        "cors.tenant-cache.max-lookups-per-ip-per-second=1000",
        "cors.tenant-cache.max-lookups-per-second=1000",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class PlatformReadEndpointsIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PW = "Sup3r-Admin-pass!";
    static final String BASE = "/api/v1/platform/tenants";
    static final AtomicInteger SEQ = new AtomicInteger();
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    /** PLAN-01 AC11: plus planName, subscriptionStatus and trialEndsAt (plan is now the plan code). */
    static final Set<String> TENANT_KEYS = Set.of("id", "slug", "displayName", "status", "plan", "planName",
            "subscriptionStatus", "trialEndsAt", "ownerEmail", "customDomain", "customDomainStatus", "createdAt", "updatedAt");
    static final Set<String> AUDIT_KEYS = Set.of("id", "tenantSlug", "action", "phase", "actorEmail", "previousStatus",
            "newStatus", "occurredAt");
    static final Set<String> PAGE_KEYS = Set.of("items", "page", "size", "totalElements", "totalPages", "sort", "direction");
    /** The tenant LIST page adds the custom-domain header facet (API-RD-07 AC2); the audit page does not. */
    static final Set<String> LIST_PAGE_KEYS = Set.of("items", "page", "size", "totalElements", "totalPages", "sort", "direction",
            "customDomainCounts");

    @Autowired MockMvc mvc;
    @Autowired PlatformAdminRepository admins;
    @Autowired TenantRepository tenantRepository;
    @Autowired com.example.perfume_budget.platform.plan.repository.PlanRepository planRepository;
    @Autowired com.example.perfume_budget.platform.plan.repository.PlanAuditLogRepository planAuditRepository;
    @Autowired org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired TenantSignupRepository signupRepository;
    @Autowired TenantAuditLogRepository audits;
    @Autowired TenantProvisioningService provisioningService;
    @Autowired TenantOriginPolicy originPolicy;
    @Autowired PlatformTransactionManager txManager;
    @Autowired PasswordEncoder encoder;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @Autowired EntityManagerFactory emf;
    @MockitoSpyBean com.example.perfume_budget.tenancy.TenantSchemaMigrator migrator;

    PlatformAdmin admin;
    Cookie adminCookie;
    String platformToken;

    @BeforeEach
    void login() throws Exception {
        admin = admins.save(PlatformAdmin.builder().email("p4root" + SEQ.incrementAndGet() + "@platform.test")
                .password(encoder.encode(PW)).fullName("Root").isActive(true).build());
        int n = SEQ.incrementAndGet();
        MockHttpServletResponse r = mvc.perform(post("/api/v1/platform/auth/login")
                        .with(req -> { req.setRemoteAddr("10.78." + (n / 250) + "." + (n % 250 + 1)); return req; })
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + PW + "\"}"))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        platformToken = r.getCookie("platformAccessToken").getValue();
        adminCookie = new Cookie("platformAccessToken", platformToken);
    }

    @AfterEach
    void reset() {
        Mockito.reset(migrator);
        dropInjectedFailures();
        TenantContext.clear();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private Tenant mk(String slug, String displayName, String email, TenantStatus status, boolean signup) {
        Tenant t = tenantRepository.saveAndFlush(Tenant.builder().slug(slug).displayName(displayName)
                .schemaName("tenant_" + slug.replace('-', '_')).status(status).planId(1L)
                .ownerEmail(email).build());
        if (signup) {
            addSignup(t);
        }
        return t;
    }

    private Tenant mk(String slug, TenantStatus status, boolean signup) {
        return mk(slug, "Shop " + slug, slug + "@p4.test", status, signup);
    }

    private void addSignup(Tenant t) {
        LocalDateTime now = LocalDateTime.now();
        signupRepository.saveAndFlush(TenantSignup.builder().tenantId(t.getId()).ownerFullName("Owner " + t.getSlug())
                .passwordHash(encoder.encode("Own3r-Passw0rd!")).otpSalt("s").otpHash("h".repeat(64))
                .otpExpiresAt(now.minusMinutes(1)).failedAttempts(0).resendCount(0).verifiedAt(now).createdAt(now).build());
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    /** GET with the query parsed into RAW request parameters (MockMvc would otherwise re-encode a pre-encoded template). */
    private MockHttpServletResponse read(String path) throws Exception {
        int q = path.indexOf('?');
        MockHttpServletRequestBuilder b = get(q < 0 ? path : path.substring(0, q)).cookie(adminCookie);
        if (q >= 0) {
            for (String pair : path.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                b = b.param(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return call(b);
    }

    private MockHttpServletResponse act(String slug, String action) throws Exception {
        return call(post(BASE + "/" + slug + "/" + action).cookie(adminCookie).header("Origin", "https://console.shopfi.example"));
    }

    private static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString());
    }

    private static Set<String> keys(JsonNode n) {
        Set<String> k = new HashSet<>();
        n.fieldNames().forEachRemaining(k::add);
        return k;
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

    private static void exec(String sql) {
        jdbc(c -> {
            try (var st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    private static long allAuditRows() {
        return scalar("SELECT count(*) FROM master.tenant_audit_log");
    }

    private List<String> auditPhases(String slug) throws Exception {
        List<String> out = new ArrayList<>();
        JsonNode items = body(read(BASE + "/" + slug + "/audit?direction=asc&size=100")).get("data").get("items");
        items.forEach(i -> out.add(i.get("phase").asText()));
        return out;
    }

    private final List<String> injected = new ArrayList<>();

    private void failAuditWrites(String slug, TenantAuditPhase phase) {
        String name = "p4_fail_audit_" + SEQ.incrementAndGet();
        exec("CREATE OR REPLACE FUNCTION master." + name + "() RETURNS trigger AS $f$ BEGIN "
                + "IF NEW.tenant_slug = '" + slug + "' AND NEW.phase = '" + phase.name() + "' THEN "
                + "RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END; $f$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER " + name + " BEFORE INSERT ON master.tenant_audit_log FOR EACH ROW EXECUTE FUNCTION master." + name + "()");
        injected.add(name);
    }

    private void dropInjectedFailures() {
        for (String name : injected) {
            exec("DROP TRIGGER IF EXISTS " + name + " ON master.tenant_audit_log");
            exec("DROP FUNCTION IF EXISTS master." + name + "()");
        }
        injected.clear();
    }

    // ---- detail ---------------------------------------------------------------------------------------------

    @Test
    void detail_returnsExactlyTheListRowKeys_andNoSecrets() throws Exception {
        Tenant t = mk("p4-detail", "Detail & Co", "owner@p4-detail.test", TenantStatus.ACTIVE, true);
        t.setWebhookToken("WEBHOOK-SECRET-TOKEN-777");
        t.setCustomDomain("shop.p4-detail.example");
        t.setCustomDomainStatus(com.example.perfume_budget.platform.enums.CustomDomainStatus.PENDING);
        t.setCustomDomainToken("p4-detail-domain-token");
        tenantRepository.saveAndFlush(t);
        MockHttpServletResponse r = read(BASE + "/p4-detail");
        assertThat(r.getStatus()).isEqualTo(200);
        String raw = r.getContentAsString();
        assertThat(raw).doesNotContain("WEBHOOK-SECRET").doesNotContain("tenant_p4_detail").doesNotContain("schemaName")
                .doesNotContain("webhookToken").doesNotContain("password").doesNotContain("$2a$").doesNotContain("Own3r");
        JsonNode root = body(r);
        assertThat(keys(root)).isEqualTo(Set.of("description", "data"));
        assertThat(root.get("description").isNull()).isTrue();
        JsonNode d = root.get("data");
        assertThat(keys(d)).isEqualTo(TENANT_KEYS);
        assertThat(d.get("slug").asText()).isEqualTo("p4-detail");
        assertThat(d.get("displayName").asText()).isEqualTo("Detail & Co");
        assertThat(d.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(d.get("plan").asText()).isEqualTo("STARTER");
        assertThat(d.get("planName").asText()).isEqualTo("Starter");
        assertThat(d.get("subscriptionStatus").asText()).isEqualTo("TRIALING");
        assertThat(d.get("ownerEmail").asText()).isEqualTo("owner@p4-detail.test");
        assertThat(d.get("customDomain").asText()).isEqualTo("shop.p4-detail.example");
        // identical to the list row of the same tenant (parity with what the list already exposes)
        JsonNode listRow = null;
        for (JsonNode i : body(read(BASE + "?search=p4-detail")).get("data").get("items")) {
            listRow = i;
        }
        assertThat(d).isEqualTo(listRow);
    }

    @Test
    void detail_unknownAndMalformedSlugs_are404_withTheSameBodyAsAnAction() throws Exception {
        String expected = "{\"description\":\"Tenant not found\",\"data\":null}";
        MockHttpServletResponse existing404 = act("no-such-shop", "suspend");
        assertThat(existing404.getStatus()).isEqualTo(404);
        assertThat(existing404.getContentAsString()).isEqualTo(expected);
        for (String slug : List.of("no-such-shop", "Acme", "ab", "a".repeat(41), "a_b", "p4-detail.")) {
            MockHttpServletResponse r = read(BASE + "/" + slug);
            assertThat(r.getStatus()).as(slug).isEqualTo(404);
            assertThat(r.getContentAsString()).as(slug).isEqualTo(expected);
            MockHttpServletResponse a = read(BASE + "/" + slug + "/audit");
            assertThat(a.getStatus()).as(slug + "/audit").isEqualTo(404);
            assertThat(a.getContentAsString()).as(slug + "/audit").isEqualTo(expected);
        }
    }

    // ---- audit: real flows -----------------------------------------------------------------------------------

    @Test
    void audit_realReprovisionAndSuspendFlows_newestFirst_exactKeys_utcOffset_noSecrets() throws Exception {
        mk("p4-flow", TenantStatus.FAILED, true);
        doThrow(new IllegalStateException("jdbc:postgresql://db.internal password=hunter2")).when(migrator).migrateTenant("tenant_p4_flow");
        assertThat(act("p4-flow", "reprovision").getStatus()).isEqualTo(500);
        Mockito.reset(migrator);
        assertThat(act("p4-flow", "reprovision").getStatus()).isEqualTo(200);
        assertThat(act("p4-flow", "suspend").getStatus()).isEqualTo(200);
        assertThat(act("p4-flow", "reactivate").getStatus()).isEqualTo(200);

        MockHttpServletResponse r = read(BASE + "/p4-flow/audit");
        assertThat(r.getStatus()).isEqualTo(200);
        String raw = r.getContentAsString();
        assertThat(raw).doesNotContain("$2a$").doesNotContain("hunter2").doesNotContain("Own3r").doesNotContain("tenant_p4_flow")
                .doesNotContain("schemaName").doesNotContain("webhook").doesNotContain("passwordHash").doesNotContain("actorAdminId")
                .doesNotContain("tenantId");
        JsonNode d = body(r).get("data");
        assertThat(keys(d)).isEqualTo(PAGE_KEYS);
        assertThat(d.get("sort").asText()).isEqualTo("id");
        assertThat(d.get("direction").asText()).isEqualTo("desc");
        assertThat(d.get("page").asInt()).isZero();
        assertThat(d.get("size").asInt()).isEqualTo(20);
        assertThat(d.get("totalElements").asInt()).isEqualTo(6);
        assertThat(d.get("totalPages").asInt()).isEqualTo(1);
        JsonNode items = d.get("items");
        List<String> got = new ArrayList<>();
        long prevId = Long.MAX_VALUE;
        OffsetDateTime prevAt = null;
        for (JsonNode i : items) {
            assertThat(keys(i)).isEqualTo(AUDIT_KEYS);
            assertThat(i.get("tenantSlug").asText()).isEqualTo("p4-flow");
            assertThat(i.get("actorEmail").asText()).isEqualTo(admin.getEmail());
            assertThat(i.get("id").asLong()).isLessThan(prevId);
            prevId = i.get("id").asLong();
            String at = i.get("occurredAt").asText();
            assertThat(at).endsWith("Z");
            OffsetDateTime parsed = OffsetDateTime.parse(at);
            assertThat(parsed.getOffset()).isEqualTo(ZoneOffset.UTC);
            if (prevAt != null) {
                assertThat(parsed).isBeforeOrEqualTo(prevAt);
            }
            prevAt = parsed;
            got.add(i.get("action").asText() + ":" + i.get("phase").asText() + ":" + i.get("previousStatus").asText()
                    + ">" + i.get("newStatus").asText());
        }
        assertThat(got).containsExactly(
                "REACTIVATE:OUTCOME:SUSPENDED>ACTIVE",
                "SUSPEND:OUTCOME:ACTIVE>SUSPENDED",
                "REPROVISION:OUTCOME:FAILED>ACTIVE",
                "REPROVISION:INTENT:FAILED>PROVISIONING",
                "REPROVISION:OUTCOME:FAILED>FAILED",
                "REPROVISION:INTENT:FAILED>PROVISIONING");
        // the page equals the database rows, id for id
        List<Long> dbIds = jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM master.tenant_audit_log WHERE tenant_slug = 'p4-flow' ORDER BY id DESC")) {
                List<Long> ids = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
                return ids;
            }
        });
        List<Long> apiIds = new ArrayList<>();
        items.forEach(i -> apiIds.add(i.get("id").asLong()));
        assertThat(apiIds).isEqualTo(dbIds);
        // occurredAt is the stored instant, not the read time
        assertThat(scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug='p4-flow' AND occurred_at > now()")).isZero();
    }

    @Test
    void audit_reconcileRow_fromTheRealReconcileFlow() throws Exception {
        mk("p4-crash", TenantStatus.PENDING, true);
        failAuditWrites("p4-crash", TenantAuditPhase.OUTCOME);
        assertThat(act("p4-crash", "approve").getStatus()).isEqualTo(200);
        dropInjectedFailures();
        assertThat(auditPhases("p4-crash")).containsExactly("INTENT");
        new PlatformTenantService(tenantRepository, signupRepository, audits, provisioningService, originPolicy,
                migrator, txManager, 1000, 15, 0, planRepository, planAuditRepository, eventPublisher).approve("p4-crash", new PlatformPrincipal(admin.getId(), admin.getEmail(), "Root"));
        JsonNode items = body(read(BASE + "/p4-crash/audit")).get("data").get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("phase").asText()).isEqualTo("RECONCILE");
        assertThat(items.get(0).get("action").asText()).isEqualTo("APPROVE");
        assertThat(items.get(0).get("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(items.get(0).get("newStatus").asText()).isEqualTo("ACTIVE");
        assertThat(items.get(1).get("phase").asText()).isEqualTo("INTENT");
        assertThat(items.get(1).get("newStatus").asText()).isEqualTo("PROVISIONING");
    }

    @Test
    void audit_readingRuleScenario_intentDead_reclaim_intent_outcome() throws Exception {
        Tenant t = mk("p4-dead", TenantStatus.FAILED, true);
        failAuditWrites("p4-dead", TenantAuditPhase.OUTCOME);
        doThrow(new IllegalStateException("boom")).when(migrator).migrateTenant("tenant_p4_dead");
        assertThat(act("p4-dead", "reprovision").getStatus()).isEqualTo(500);   // INTENT written, OUTCOME lost
        dropInjectedFailures();
        Mockito.reset(migrator);
        // the saga "died" while PROVISIONING three hours ago
        exec("UPDATE master.tenants SET status = 'PROVISIONING', updated_at = now() - interval '3 hours' WHERE id = " + t.getId());
        assertThat(act("p4-dead", "reprovision").getStatus()).isEqualTo(200);   // RECLAIM, INTENT, OUTCOME
        assertThat(auditPhases("p4-dead")).containsExactly("INTENT", "RECLAIM", "INTENT", "OUTCOME");
        JsonNode items = body(read(BASE + "/p4-dead/audit")).get("data").get("items");
        assertThat(items.get(0).get("phase").asText()).isEqualTo("OUTCOME");
        assertThat(items.get(2).get("phase").asText()).isEqualTo("RECLAIM");
        assertThat(items.get(2).get("previousStatus").asText()).isEqualTo("PROVISIONING");
        assertThat(items.get(2).get("newStatus").asText()).isEqualTo("FAILED");
        assertThat(items.get(2).get("action").asText()).isEqualTo("REPROVISION");
        assertThat(items.get(3).get("phase").asText()).isEqualTo("INTENT");
    }

    // ---- audit: paging, sort, limits ---------------------------------------------------------------------------

    @Test
    void audit_paging_sortAllowList_limits_andTenantSeparation() throws Exception {
        mk("p4-page", TenantStatus.ACTIVE, false);
        mk("p4-other", TenantStatus.ACTIVE, false);
        for (int i = 0; i < 3; i++) {
            assertThat(act("p4-page", "suspend").getStatus()).isEqualTo(200);
            assertThat(act("p4-page", "reactivate").getStatus()).isEqualTo(200);
        }
        assertThat(act("p4-other", "suspend").getStatus()).isEqualTo(200);

        JsonNode all = body(read(BASE + "/p4-page/audit?size=1000")).get("data");
        assertThat(all.get("size").asInt()).isEqualTo(100);
        assertThat(all.get("totalElements").asInt()).isEqualTo(6);
        List<Long> desc = new ArrayList<>();
        all.get("items").forEach(i -> {
            assertThat(i.get("tenantSlug").asText()).isEqualTo("p4-page");
            desc.add(i.get("id").asLong());
        });
        assertThat(desc).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        List<Long> paged = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            JsonNode d = body(read(BASE + "/p4-page/audit?size=2&page=" + p)).get("data");
            assertThat(d.get("totalPages").asInt()).isEqualTo(3);
            assertThat(d.get("items")).hasSize(2);
            d.get("items").forEach(i -> paged.add(i.get("id").asLong()));
        }
        assertThat(paged).isEqualTo(desc);
        assertThat(body(read(BASE + "/p4-page/audit?size=2&page=3")).get("data").get("items")).isEmpty();

        JsonNode asc = body(read(BASE + "/p4-page/audit?direction=ASC&sort=id")).get("data");
        assertThat(asc.get("direction").asText()).isEqualTo("asc");
        List<Long> ascIds = new ArrayList<>();
        asc.get("items").forEach(i -> ascIds.add(i.get("id").asLong()));
        assertThat(ascIds).isSorted();
        JsonNode byTime = body(read(BASE + "/p4-page/audit?sort=occurredAt&direction=desc")).get("data");
        assertThat(byTime.get("sort").asText()).isEqualTo("occurredAt");
        assertThat(byTime.get("items")).hasSize(6);

        for (String q : List.of("?page=-1", "?size=0", "?size=-5", "?size=abc", "?page=x", "?sort=actorEmail", "?sort=tenantId",
                "?sort=phase", "?sort=id;drop", "?sort=createdAt", "?direction=up", "?direction=desc;--")) {
            MockHttpServletResponse r = read(BASE + "/p4-page/audit" + q);
            assertThat(r.getStatus()).as(q).isEqualTo(400);
            assertThat(keys(body(r))).isEqualTo(Set.of("description", "data"));
            assertThat(body(r).get("data").isNull()).isTrue();
        }
        // a tenant with no rows: empty items, not a 404
        mk("p4-empty", TenantStatus.ACTIVE, false);
        JsonNode empty = body(read(BASE + "/p4-empty/audit")).get("data");
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalElements").asInt()).isZero();
    }

    @Test
    void reads_writeNoAuditRowsAndChangeNothing() throws Exception {
        Tenant t = mk("p4-ro", TenantStatus.ACTIVE, false);
        assertThat(act("p4-ro", "suspend").getStatus()).isEqualTo(200);
        long rows = allAuditRows();
        LocalDateTime updated = tenantRepository.findBySlug("p4-ro").orElseThrow().getUpdatedAt();
        for (int i = 0; i < 3; i++) {
            assertThat(read(BASE + "/p4-ro").getStatus()).isEqualTo(200);
            assertThat(read(BASE + "/p4-ro/audit").getStatus()).isEqualTo(200);
            assertThat(read(BASE + "?search=p4-ro").getStatus()).isEqualTo(200);
            assertThat(read(BASE + "/nope-nope/audit").getStatus()).isEqualTo(404);
            assertThat(read(BASE + "/p4-ro/audit?sort=bad").getStatus()).isEqualTo(400);
        }
        assertThat(allAuditRows()).isEqualTo(rows);
        assertThat(tenantRepository.findBySlug("p4-ro").orElseThrow().getUpdatedAt()).isEqualTo(updated);
        assertThat(tenantRepository.findBySlug("p4-ro").orElseThrow().getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(t.getId()).isNotNull();
    }

    @Test
    void readMethods_areReadOnlyTransactions() throws Exception {
        for (String name : List.of("detail", "audit", "list")) {
            boolean found = false;
            for (var m : PlatformTenantService.class.getDeclaredMethods()) {
                if (m.getName().equals(name) && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    Transactional tx = m.getAnnotation(Transactional.class);
                    assertThat(tx).as(name).isNotNull();
                    assertThat(tx.readOnly()).as(name).isTrue();
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
        // and the controller class itself still carries the class-level SUPER_ADMIN guard
        var pre = PlatformTenantController.class.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(pre.value()).isEqualTo("hasRole('SUPER_ADMIN')");
    }

    @Test
    void audit_andDetail_haveNoNPlusOne_statementCountIsIndependentOfRows() throws Exception {
        mk("p4-n1a", TenantStatus.ACTIVE, false);
        mk("p4-n1b", TenantStatus.ACTIVE, false);
        for (int i = 0; i < 8; i++) {
            act("p4-n1b", i % 2 == 0 ? "suspend" : "reactivate");
        }
        act("p4-n1a", "suspend");
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        read(BASE + "/p4-n1a/audit"); // warm up (auth lookups, caches)
        read(BASE + "/p4-n1b/audit"); // warm up the other slug too
        long before = sf.getStatistics().getPrepareStatementCount();
        read(BASE + "/p4-n1a/audit");
        long few = sf.getStatistics().getPrepareStatementCount() - before;
        before = sf.getStatistics().getPrepareStatementCount();
        MockHttpServletResponse many = read(BASE + "/p4-n1b/audit");
        long lots = sf.getStatistics().getPrepareStatementCount() - before;
        assertThat(body(many).get("data").get("items")).hasSize(8);
        assertThat(lots).as("8 rows vs 1 row (few=" + few + ")").isEqualTo(few);
        assertThat(lots).isLessThanOrEqualTo(6);
        before = sf.getStatistics().getPrepareStatementCount();
        read(BASE + "/p4-n1b");
        assertThat(sf.getStatistics().getPrepareStatementCount() - before).isLessThanOrEqualTo(5);
    }

    // ---- search -------------------------------------------------------------------------------------------------

    private void searchFixtures() {
        if (tenantRepository.existsBySlug("p4s-alpha")) {
            return;
        }
        mk("p4s-alpha", "Alpha 100% Cotton", "owner-alpha@p4s.test", TenantStatus.ACTIVE, false);
        mk("p4s-beta", "Beta_Shop", "beta.owner@p4s.test", TenantStatus.FAILED, false);
        mk("p4s-gamma", "O'Brien Gamma back\\slash", "gamma@p4s.test", TenantStatus.ACTIVE, false);
        mk("p4s-delta", "Delta Perfumes", "Mixed.Case@P4S.test", TenantStatus.FAILED, false);
        mk("p4s-abc", "Abc Store", "abc@p4s.test", TenantStatus.ACTIVE, false);
    }

    private List<String> slugs(String query) throws Exception {
        MockHttpServletResponse r = read(BASE + query);
        assertThat(r.getStatus()).as(query + " " + r.getContentAsString()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).get("data").get("items").forEach(i -> out.add(i.get("slug").asText()));
        return out;
    }

    private static String enc(String s) {
        return s; // read() passes raw parameter values
    }

    @Test
    void search_matchesSlugNameAndEmail_caseInsensitive_andAcrossAllThree() throws Exception {
        searchFixtures();
        assertThat(slugs("?search=p4s-alp")).containsExactly("p4s-alpha");              // slug
        assertThat(slugs("?search=ALPHA 100")).containsExactly("p4s-alpha");            // display name, other case
        assertThat(slugs("?search=" + enc("delta perf"))).containsExactly("p4s-delta");
        assertThat(slugs("?search=mixed.case@p4s")).containsExactly("p4s-delta");       // email, lower vs stored mixed
        assertThat(slugs("?search=MIXED.CASE@P4S.TEST")).containsExactly("p4s-delta");
        assertThat(slugs("?search=" + enc("  beta.owner  "))).containsExactly("p4s-beta"); // trimmed
        assertThat(slugs("?search=p4s.test&sort=slug&direction=asc&size=100")).containsExactly(
                "p4s-abc", "p4s-alpha", "p4s-beta", "p4s-delta", "p4s-gamma");
        assertThat(slugs("?search=zzzz-no-match-p4")).isEmpty();
        JsonNode d = body(read(BASE + "?search=p4s-")).get("data");
        assertThat(d.get("totalElements").asInt()).isEqualTo(5);
        assertThat(keys(d)).isEqualTo(LIST_PAGE_KEYS);
        for (JsonNode i : d.get("items")) {
            assertThat(keys(i)).isEqualTo(TENANT_KEYS);
        }
    }

    @Test
    void search_wildcardsAreLiteral_percentUnderscoreBackslash() throws Exception {
        searchFixtures();
        // '%' only matches the tenant whose text really contains a percent sign
        List<String> pct = slugs("?search=" + enc("%") + "&size=100");
        assertThat(pct).contains("p4s-alpha").doesNotContain("p4s-beta", "p4s-gamma", "p4s-delta", "p4s-abc");
        assertThat(slugs("?search=" + enc("100%"))).containsExactly("p4s-alpha");
        assertThat(slugs("?search=" + enc("%cotton"))).isEmpty();                         // '%' is not "anything"
        assertThat(slugs("?search=" + enc("a%a"))).isEmpty();
        // '_' only matches a real underscore: "p4s-delt_" must not match "p4s-delta", "ab_" must not match "abc"
        List<String> us = slugs("?search=" + enc("_") + "&size=100");
        assertThat(us).contains("p4s-beta").doesNotContain("p4s-alpha", "p4s-gamma", "p4s-delta", "p4s-abc");
        assertThat(slugs("?search=" + enc("p4s-delt_"))).isEmpty();
        assertThat(slugs("?search=" + enc("a_c"))).isEmpty();
        assertThat(slugs("?search=" + enc("beta_shop"))).containsExactly("p4s-beta");
        // backslash is literal too (and does not turn the next wildcard back on)
        assertThat(slugs("?search=" + enc("back\\slash"))).containsExactly("p4s-gamma");
        assertThat(slugs("?search=" + enc("\\"))).containsExactly("p4s-gamma");
        assertThat(slugs("?search=" + enc("\\%"))).isEmpty();
        assertThat(slugs("?search=" + enc("\\_"))).isEmpty();
        assertThat(slugs("?search=" + enc("%%"))).isEmpty();
    }

    @Test
    void search_quotesAndInjectionAttempts_areDataNotSql() throws Exception {
        searchFixtures();
        long tenantsBefore = scalar("SELECT count(*) FROM master.tenants");
        assertThat(slugs("?search=" + enc("O'Brien"))).containsExactly("p4s-gamma");
        assertThat(slugs("?search=" + enc("'"))).containsExactly("p4s-gamma");           // a quote is just a character
        for (String evil : List.of("''", "' OR '1'='1", "'; DROP TABLE master.tenants; --", "\" OR 1=1 --",
                "x') OR ('1'='1", "%' OR slug LIKE '%", "p4s' UNION SELECT 1 --")) {
            MockHttpServletResponse r = read(BASE + "?search=" + enc(evil));
            assertThat(r.getStatus()).as(evil).isEqualTo(200);
            assertThat(body(r).get("data").get("items")).as(evil).isEmpty();
        }
        assertThat(scalar("SELECT count(*) FROM master.tenants")).isEqualTo(tenantsBefore);
        // control characters (a NUL would be a database error) are a clean 400, same envelope
        for (String ctl : List.of("\u0000", "a\u0000b", "a\nb", "\u001f", "\u007f")) {
            MockHttpServletResponse r = read(BASE + "?search=" + ctl);
            assertThat(r.getStatus()).as("control char " + (int) ctl.charAt(0)).isEqualTo(400);
            assertThat(r.getContentAsString()).isEqualTo("{\"description\":\"search contains invalid characters\",\"data\":null}");
        }
    }

    @Test
    void search_lengthCap_blankAndCombinationWithFiltersAndPaging() throws Exception {
        searchFixtures();
        assertThat(read(BASE + "?search=" + "a".repeat(100)).getStatus()).isEqualTo(200);
        for (String tooLong : List.of("a".repeat(101), "a".repeat(5000), "%".repeat(101), " " + "a".repeat(101))) {
            MockHttpServletResponse r = read(BASE + "?search=" + enc(tooLong));
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getContentAsString()).isEqualTo("{\"description\":\"search must be at most 100 characters\",\"data\":null}");
        }
        // blank means "no search"
        assertThat(slugs("?search=&size=100")).contains("p4s-alpha", "p4s-beta");
        assertThat(slugs("?search=" + enc("   ") + "&size=100")).contains("p4s-alpha", "p4s-beta");
        // status AND search
        assertThat(slugs("?search=p4s-&status=failed&sort=slug&direction=asc")).containsExactly("p4s-beta", "p4s-delta");
        assertThat(slugs("?search=p4s-&status=ACTIVE&sort=slug&direction=desc")).containsExactly("p4s-gamma", "p4s-alpha", "p4s-abc");
        // paging over search results is stable and totals count matches only
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            JsonNode d = body(read(BASE + "?search=p4s-&size=1&page=" + p + "&sort=slug&direction=asc")).get("data");
            assertThat(d.get("totalElements").asInt()).isEqualTo(5);
            assertThat(d.get("totalPages").asInt()).isEqualTo(5);
            paged.add(d.get("items").get(0).get("slug").asText());
        }
        assertThat(paged).containsExactly("p4s-abc", "p4s-alpha", "p4s-beta", "p4s-delta", "p4s-gamma");
        // the sort allow-list is unchanged by search; bad status still 400
        assertThat(read(BASE + "?search=p4s&sort=ownerEmail").getStatus()).isEqualTo(400);
        assertThat(read(BASE + "?search=p4s&sort=schemaName").getStatus()).isEqualTo(400);
        assertThat(read(BASE + "?search=p4s&status=nope").getStatus()).isEqualTo(400);
    }

    // ---- security: actor matrix on the three new endpoints --------------------------------------------------------

    private List<String> readPaths() {
        return List.of(BASE, BASE + "?search=p4", BASE + "/p4-sec", BASE + "/p4-sec/audit", BASE + "/no-such-shop",
                BASE + "/no-such-shop/audit");
    }

    @Test
    void actorMatrix_everyNonPlatformActorIsDenied_onDetailAuditAndSearch() throws Exception {
        mk("p4-sec", TenantStatus.ACTIVE, false);
        act("p4-sec", "suspend");
        TestTenant a = tenants.ensureTenant("p4-ta");
        TestTenant b = tenants.ensureTenant("p4-tb");
        String adminTok = tenants.runAs(a, () -> jwtUtil.generateAccessToken(a.adminEmail(), "ADMIN", 1L, "Admin"));
        String customerTok = tenants.runAs(a, () -> jwtUtil.generateAccessToken("c@p4-ta.test", "CUSTOMER", 2L, "Cust"));
        String frontTok = tenants.runAs(a, () -> jwtUtil.generateAccessToken("f@p4-ta.test", "FRONT_DESK", 3L, "Desk"));
        String refreshTok = tenants.runAs(a, () -> jwtUtil.generateRefreshToken(a.adminEmail(), "ADMIN", 1L, "Admin"));
        String foreignTok = tenants.runAs(b, () -> jwtUtil.generateAccessToken(b.adminEmail(), "ADMIN", 1L, "Admin"));
        // worst case: a tenant user with the same email as the real platform admin
        String sameEmailTok = tenants.runAs(a, () -> jwtUtil.generateAccessToken(admin.getEmail(), "ADMIN", 1L, "Admin"));
        long rows = allAuditRows();

        List<Cookie> actors = List.of(
                new Cookie("accessToken", adminTok), new Cookie("accessToken", customerTok), new Cookie("accessToken", frontTok),
                new Cookie("accessToken", foreignTok), new Cookie("platformAccessToken", adminTok),
                new Cookie("platformAccessToken", customerTok), new Cookie("platformAccessToken", frontTok),
                new Cookie("platformAccessToken", foreignTok), new Cookie("platformAccessToken", sameEmailTok),
                new Cookie("platformAccessToken", refreshTok), new Cookie("platformRefreshToken", refreshTok),
                new Cookie("refreshToken", refreshTok),
                // a platform token in the tenant cookie name must not work on the platform route either
                new Cookie("accessToken", platformToken), new Cookie("refreshToken", platformToken));
        for (String path : readPaths()) {
            assertThat(call(get(path)).getStatus()).as("anonymous " + path).isEqualTo(401);
            for (Cookie c : actors) {
                MockHttpServletResponse r = call(get(path).cookie(c));
                assertThat(r.getStatus()).as(c.getName() + " " + path).isEqualTo(401);
                assertThat(r.getContentAsString()).as(c.getName() + " " + path).doesNotContain("p4-sec").doesNotContain("ownerEmail");
                MockHttpServletResponse bearer = call(get(path).header("Authorization", "Bearer " + adminTok).cookie(c));
                assertThat(bearer.getStatus()).as("bearer " + c.getName() + " " + path).isIn(401, 403);
                MockHttpServletResponse fromTenant = call(get(path).cookie(c).header("Origin", "https://p4-ta.shopfi.test"));
                assertThat(fromTenant.getStatus()).as("tenant origin " + c.getName() + " " + path).isIn(401, 403);
                assertThat(fromTenant.getContentAsString()).doesNotContain("ownerEmail");
            }
            // the real platform session still works from a non-browser client (control)
            assertThat(call(get(path).cookie(adminCookie)).getStatus()).as("control " + path).isIn(200, 404, 400);
        }
        assertThat(allAuditRows()).isEqualTo(rows);
    }

    @Test
    void platformToken_onTenantRoutes_isAnonymous_andTenantRoutesNeverServePlatformData() throws Exception {
        mk("p4-leak", TenantStatus.ACTIVE, false);
        TestTenant a = tenants.ensureTenant("p4-ta");
        for (String path : List.of("/api/v1/admin/accounting/income-statement", "/api/v1/auth/me")) {
            for (String name : List.of("accessToken", "platformAccessToken")) {
                MockHttpServletResponse r = call(get(path).cookie(new Cookie(name, platformToken))
                        .header("Origin", "https://p4-ta.shopfi.test"));
                assertThat(r.getStatus()).as(name + " " + path).isEqualTo(401);
                assertThat(r.getContentAsString()).doesNotContain("p4-leak").doesNotContain("ownerEmail");
            }
        }
        // the tenant API under a tenant host has no platform routes: a platform path is not served as tenant data
        MockHttpServletResponse viaTenantHost = call(get(BASE + "/p4-leak").cookie(adminCookie).header("Origin", "https://p4-ta.shopfi.test"));
        assertThat(viaTenantHost.getStatus()).isIn(401, 403);
        assertThat(viaTenantHost.getContentAsString()).doesNotContain("p4-leak");
        assertThat(a.slug()).isEqualTo("p4-ta");
    }

    @Test
    void deactivatedAdmin_losesTheNewReadsOnTheNextRequest() throws Exception {
        mk("p4-gone", TenantStatus.ACTIVE, false);
        assertThat(read(BASE + "/p4-gone").getStatus()).isEqualTo(200);
        assertThat(read(BASE + "/p4-gone/audit").getStatus()).isEqualTo(200);
        admin.setActive(false);
        admins.save(admin);
        assertThat(read(BASE + "/p4-gone").getStatus()).isEqualTo(401);
        assertThat(read(BASE + "/p4-gone/audit").getStatus()).isEqualTo(401);
        assertThat(read(BASE + "?search=p4").getStatus()).isEqualTo(401);
    }

    @Test
    void writeVerbsOnTheNewPaths_stillDoNotExist() throws Exception {
        mk("p4-verbs", TenantStatus.ACTIVE, false);
        long rows = allAuditRows();
        for (MockHttpServletRequestBuilder b : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(BASE + "/p4-verbs"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(BASE + "/p4-verbs"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/p4-verbs"),
                post(BASE + "/p4-verbs/audit"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(BASE + "/p4-verbs/audit"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/p4-verbs/audit"))) {
            assertThat(call(b.cookie(adminCookie).header("Origin", "https://console.shopfi.example")).getStatus())
                    .as(b.toString()).isBetween(400, 499);
        }
        assertThat(allAuditRows()).isEqualTo(rows);
        assertThat(tenantRepository.findBySlug("p4-verbs")).isPresent();
    }

    // ---- review fixes (F1, F2, F3, F4, F6, F8) -------------------------------------------------------------------

    private Set<Long> auditIds(String slug) throws Exception {
        Set<Long> ids = new HashSet<>();
        for (JsonNode i : body(read(BASE + "/" + slug + "/audit?size=100")).get("data").get("items")) {
            assertThat(i.get("tenantSlug").asText()).isEqualTo(slug);
            ids.add(i.get("id").asLong());
        }
        return ids;
    }

    @Test
    void audit_prefixSlugs_acme_acmeStore_acmex_neverSeeEachOthersRows() throws Exception {
        mk("p4-acme", TenantStatus.ACTIVE, false);
        mk("p4-acme-store", TenantStatus.ACTIVE, false);
        mk("p4-acmex", TenantStatus.ACTIVE, false);
        act("p4-acme", "suspend");
        act("p4-acme-store", "suspend");
        act("p4-acme-store", "reactivate");
        act("p4-acmex", "suspend");
        act("p4-acmex", "reactivate");
        act("p4-acmex", "suspend");
        Set<Long> a = auditIds("p4-acme");
        Set<Long> b = auditIds("p4-acme-store");
        Set<Long> c = auditIds("p4-acmex");
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(2);
        assertThat(c).hasSize(3);
        assertThat(a).doesNotContainAnyElementsOf(b).doesNotContainAnyElementsOf(c);
        assertThat(b).doesNotContainAnyElementsOf(c);
        for (String s : List.of("p4-acme", "p4-acme-store", "p4-acmex")) {
            assertThat(body(read(BASE + "/" + s + "/audit")).get("data").get("totalElements").asInt())
                    .isEqualTo(s.equals("p4-acme") ? 1 : s.equals("p4-acme-store") ? 2 : 3);
        }
    }

    @Test
    void audit_reusedSlug_doesNotInheritAPurgedTenantsHistory() throws Exception {
        Tenant old = mk("p4-reuse", TenantStatus.ACTIVE, false);
        act("p4-reuse", "suspend");
        assertThat(auditIds("p4-reuse")).hasSize(1);
        tenantRepository.deleteById(old.getId());
        tenantRepository.flush();
        assertThat(read(BASE + "/p4-reuse/audit").getStatus()).isEqualTo(404);   // purged: gone, rows remain in the table
        assertThat(scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug = 'p4-reuse'")).isEqualTo(1);
        Tenant reborn = mk("p4-reuse", TenantStatus.ACTIVE, false);
        assertThat(reborn.getId()).isNotEqualTo(old.getId());
        JsonNode d = body(read(BASE + "/p4-reuse/audit")).get("data");
        assertThat(d.get("items")).isEmpty();                                     // new tenant, empty history
        assertThat(d.get("totalElements").asInt()).isZero();
        act("p4-reuse", "suspend");
        assertThat(auditIds("p4-reuse")).hasSize(1);
        assertThat(scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug = 'p4-reuse'")).isEqualTo(2);
    }

    @Test
    void reads_areNotCacheable_detailAuditSearchAndTheirErrors() throws Exception {
        mk("p4-cache", TenantStatus.ACTIVE, false);
        act("p4-cache", "suspend");
        for (String path : List.of(BASE + "/p4-cache", BASE + "/p4-cache/audit", BASE + "?search=p4-cache", BASE,
                BASE + "/p4-cache/audit?sort=bad", BASE + "/no-such-shop")) {
            MockHttpServletResponse r = read(path);
            assertThat(r.getHeader("Cache-Control")).as(path).contains("no-store");
            assertThat(r.getHeader("Pragma")).as(path).isEqualTo("no-cache");
        }
    }

    @Test
    void page_isCapped_boundaryValues_onAuditAndList() throws Exception {
        mk("p4-pagecap", TenantStatus.ACTIVE, false);
        for (String base : List.of(BASE + "/p4-pagecap/audit", BASE)) {
            assertThat(read(base + "?page=1000000").getStatus()).as(base).isEqualTo(200);
            assertThat(body(read(base + "?page=1000000")).get("data").get("items")).isEmpty();
            for (String bad : List.of("1000001", "2147483647", "-1", "2147483648", "9999999999")) {
                MockHttpServletResponse r = read(base + "?page=" + bad);
                assertThat(r.getStatus()).as(base + " page=" + bad).isEqualTo(400);
                assertThat(keys(body(r))).isEqualTo(Set.of("description", "data"));
            }
            assertThat(read(base + "?page=1000001").getContentAsString())
                    .isEqualTo("{\"description\":\"page must be <= 1000000\",\"data\":null}");
            assertThat(read(base + "?page=1000000&size=100").getStatus()).as(base).isEqualTo(200);
        }
        assertThat(read(BASE + "?page=1000001&search=x").getStatus()).isEqualTo(400);
    }

    @Test
    void audit_identicalTimestamps_pageStablyByIdTieBreak() throws Exception {
        Tenant t = mk("p4-ties", TenantStatus.ACTIVE, false);
        // ids are inserted in DECREASING order, so heap/index order for the equal timestamps is the opposite of the id order:
        // without the id tie-break the database returns the ties in its physical order and the assertions below fail
        exec("INSERT INTO master.tenant_audit_log (id, occurred_at, actor_admin_id, actor_email, tenant_id, tenant_slug, action,"
                + " previous_status, new_status, phase) SELECT 900000000 + g, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, 'tie@platform.test', "
                + t.getId() + ", 'p4-ties', 'SUSPEND', 'ACTIVE', 'SUSPENDED', 'OUTCOME' FROM generate_series(7, 1, -1) g");
        assertThat(scalar("SELECT count(DISTINCT occurred_at) FROM master.tenant_audit_log WHERE tenant_slug = 'p4-ties'")).isEqualTo(1);
        List<Long> dbDesc = jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM master.tenant_audit_log WHERE tenant_slug = 'p4-ties' ORDER BY id DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        });
        assertThat(dbDesc).hasSize(7);
        for (String sort : List.of("occurredAt", "id")) {
            for (String dir : List.of("desc", "asc")) {
                List<Long> expected = new ArrayList<>(dbDesc);
                if (dir.equals("asc")) {
                    java.util.Collections.reverse(expected);
                }
                List<Long> paged = new ArrayList<>();
                for (int p = 0; p < 4; p++) {
                    JsonNode items = body(read(BASE + "/p4-ties/audit?size=2&page=" + p + "&sort=" + sort + "&direction=" + dir))
                            .get("data").get("items");
                    items.forEach(i -> paged.add(i.get("id").asLong()));
                }
                assertThat(paged).as(sort + " " + dir).isEqualTo(expected);
            }
        }
    }

    @Test
    void search_foldingIsDoneByTheDatabase_dottedCapitalI_matchesItself() throws Exception {
        mk("p4-ist", "\u0130stanbul Perfumes", "ist@p4i.test", TenantStatus.ACTIVE, false);
        mk("p4-ist2", "Plain Shop", "ist2@p4i.test", TenantStatus.ACTIVE, false);
        assertThat(slugs("?search=\u0130STANBUL")).containsExactly("p4-ist");
        assertThat(slugs("?search=\u0130stanbul")).containsExactly("p4-ist");
        assertThat(slugs("?search=" + "\u0130stanbul Perf")).containsExactly("p4-ist");
        assertThat(slugs("?search=P4I.TEST&sort=slug&direction=asc")).containsExactly("p4-ist", "p4-ist2");
    }
}

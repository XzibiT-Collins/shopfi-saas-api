package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.TestPlans;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PLAN-01 AC8 (tenant plan action), AC10 (public list) and the trial clock (plans.md section 15, Security L5), on the real
 * filter chain and PostgreSQL.
 */
class TenantPlanAndPublicPlansIntegrationTest extends AbstractPlansIntegrationTest {

    static final String TENANTS = "/api/v1/platform/tenants";

    @Autowired TenantRepository tenantRepository;
    @Autowired TenantProvisioningService provisioning;

    private Tenant fresh(String slug, TenantStatus status) {
        tenantRepository.findBySlug(slug).ifPresent(t -> {
            exec("DELETE FROM master.tenant_signups WHERE tenant_id = ?", t.getId());
            exec("DELETE FROM master.tenants WHERE id = ?", t.getId());
        });
        return tenantRepository.saveAndFlush(Tenant.builder().slug(slug).displayName("Shop " + slug)
                .schemaName("tenant_" + slug.replace('-', '_')).status(status).planId(TestPlans.GROWTH_ID)
                .ownerEmail("owner@" + slug + ".test").build());
    }

    private MockHttpServletResponse change(String slug, String body) throws Exception {
        return asAdmin(put(TENANTS + "/" + slug + "/plan"), body);
    }

    private static Instant trialEnd(String slug) {
        String s = string("SELECT to_char(trial_ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM master.tenants WHERE slug = ?", slug);
        return s == null ? null : Instant.parse(s);
    }

    private static long tenantAuditRows(String slug, String action) {
        return scalar("SELECT count(*) FROM master.tenant_audit_log WHERE tenant_slug = ? AND action = ?", slug, action);
    }

    // ------------------------------------------------------------------------------------------ AC8

    @Test
    void changePlan_setsThePlanCode_keepsStatusAndTrial_auditsInBothLogs_andPublishesTheEvictionEvent() throws Exception {
        Tenant t = fresh("tp-move", TenantStatus.ACTIVE);
        exec("UPDATE master.tenants SET trial_ends_at = now() + interval '5 days' WHERE id = ?", t.getId());
        Instant trial = trialEnd("tp-move");
        long planAudit = auditRows();
        MockHttpServletResponse r = change("tp-move", "{\"planId\":3}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        Set<String> keys = new HashSet<>();
        d.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("id", "slug", "displayName", "status", "plan", "planName", "subscriptionStatus",
                "trialEndsAt", "ownerEmail", "customDomain", "customDomainStatus", "createdAt", "updatedAt");
        assertThat(d.get("plan").asText()).isEqualTo("SCALE");
        assertThat(d.get("planName").asText()).isEqualTo("Scale");
        assertThat(d.get("subscriptionStatus").asText()).isEqualTo("TRIALING");
        assertThat(Instant.parse(d.get("trialEndsAt").asText())).isEqualTo(trial);
        assertThat(d.get("trialEndsAt").asText()).endsWith("Z");
        assertThat(d.get("status").asText()).isEqualTo("ACTIVE");
        // one plan audit row and one tenant audit row (CHANGE_PLAN, OUTCOME, previous = new registry status)
        assertThat(auditRows()).isEqualTo(planAudit + 1);
        assertThat(JSON.readTree(string("SELECT changes::text FROM master.plan_audit_log ORDER BY id DESC LIMIT 1")))
                .isEqualTo(JSON.readTree("[{\"field\": \"planCode\", \"before\": \"GROWTH\", \"after\": \"SCALE\"}]"));
        assertThat(string("SELECT action || '/' || plan_code || '/' || tenant_slug || '/' || actor_email || '/' || actor_admin_id "
                + "FROM master.plan_audit_log ORDER BY id DESC LIMIT 1"))
                .isEqualTo("TENANT_PLAN_UPDATE/SCALE/tp-move/" + admin.getEmail() + "/" + admin.getId());
        assertThat(string("SELECT action || '/' || phase || '/' || previous_status || '/' || new_status FROM master.tenant_audit_log "
                + "WHERE tenant_slug = 'tp-move' ORDER BY id DESC LIMIT 1")).isEqualTo("CHANGE_PLAN/OUTCOME/ACTIVE/ACTIVE");
        JsonNode tenantAudit = body(asAdmin(get(TENANTS + "/tp-move/audit"), null)).get("data").get("items").get(0);
        assertThat(tenantAudit.get("action").asText()).isEqualTo("CHANGE_PLAN");
        JsonNode planAuditPage = body(asAdmin(get(PLANS + "/audit?tenantSlug=tp-move"), null)).get("data").get("items");
        assertThat(planAuditPage).hasSize(1);
        assertThat(planAuditPage.get(0).get("tenantSlug").asText()).isEqualTo("tp-move");
        assertThat(planAuditPage.get(0).get("planId").asLong()).isEqualTo(3);
        assertThat(EventCapture.COMMITTED).containsExactly(new TenantSubscriptionChangedEvent(t.getId()));

        // the same request again changes nothing and writes nothing
        assertThat(change("tp-move", "{\"planId\":3}").getStatus()).isEqualTo(200);
        assertThat(auditRows()).isEqualTo(planAudit + 1);
        assertThat(tenantAuditRows("tp-move", "CHANGE_PLAN")).isEqualTo(1);
        // the tenant list shows the plan fields too
        JsonNode list = body(asAdmin(get(TENANTS + "?search=tp-move"), null)).get("data").get("items").get(0);
        assertThat(list.get("plan").asText()).isEqualTo("SCALE");
        assertThat(list.get("planName").asText()).isEqualTo("Scale");
    }

    @Test
    void inactivePlan_needsAllowInactive_unknownPlanIs400_shapeRulesAre400() throws Exception {
        fresh("tp-rules", TenantStatus.ACTIVE);
        exec("UPDATE master.plans SET active = FALSE WHERE id = 3");
        MockHttpServletResponse inactive = change("tp-rules", "{\"planId\":3}");
        assertThat(inactive.getStatus()).isEqualTo(400);
        assertThat(body(inactive).toString()).isEqualTo(
                "{\"description\":\"The plan is inactive; send allowInactive to assign it anyway\",\"data\":{\"code\":\"PLAN_INACTIVE\"}}");
        assertThat(change("tp-rules", "{\"planId\":3,\"allowInactive\":false}").getStatus()).isEqualTo(400);
        MockHttpServletResponse allowed = change("tp-rules", "{\"planId\":3,\"allowInactive\":true}");
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(body(allowed).get("data").get("plan").asText()).isEqualTo("SCALE");

        MockHttpServletResponse unknown = change("tp-rules", "{\"planId\":9999}");
        assertThat(body(unknown).toString()).isEqualTo("{\"description\":\"Validation failed\",\"data\":{\"planId\":\"unknown plan\"}}");
        long audit = auditRows();
        for (String bad : List.of("{}", "{\"trialEndsAt\":\"2026-12-31T23:59:59Z\",\"subscriptionStatus\":\"ACTIVE\"}",
                "{\"subscriptionStatus\":\"ACTIVE\",\"allowInactive\":true}", "{\"subscriptionStatus\":\"TRIALING\"}",
                "{\"subscriptionStatus\":\"PAST_DUE\"}", "{\"subscriptionStatus\":\"active\"}", "{\"planId\":0}",
                "{\"planId\":1.5}", "{\"planId\":\"3\"}", "{\"trialEndsAt\":\"not-a-date\"}", "not json")) {
            MockHttpServletResponse r = change("tp-rules", bad);
            assertThat(r.getStatus()).as(bad + " -> " + r.getContentAsString()).isEqualTo(400);
        }
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(auditRows()).isEqualTo(audit);
        assertThat(string("SELECT subscription_status FROM master.tenants WHERE slug = 'tp-rules'")).isEqualTo("TRIALING");
        // unknown and malformed slugs: the platform 404, identical
        for (String slug : List.of("no-such-shop", "Bad_Slug", "ab", "a".repeat(41), "x'--")) {
            MockHttpServletResponse r = change(slug, "{\"planId\":2}");
            assertThat(r.getStatus()).as(slug).isEqualTo(404);
            assertThat(body(r).toString()).isEqualTo("{\"description\":\"Tenant not found\",\"data\":null}");
        }
    }

    @Test
    void trialEndsAt_extendsOnlyIntoTheFuture_atMost365Days_onlyWhileTrialingOrCanceled_andRevivesACanceledTrial() throws Exception {
        fresh("tp-trial", TenantStatus.ACTIVE);
        exec("UPDATE master.tenants SET trial_ends_at = now() + interval '10 days' WHERE slug = 'tp-trial'");
        Instant current = trialEnd("tp-trial");
        Instant now = Instant.now();
        String msg = "{\"description\":\"Validation failed\",\"data\":{\"trialEndsAt\":\"must be later than the current trial end and at most 365 days ahead\"}}";
        for (Instant bad : List.of(now.minus(Duration.ofDays(1)), current, current.minusSeconds(60),
                now.plus(Duration.ofDays(366)))) {
            MockHttpServletResponse r = change("tp-trial", "{\"trialEndsAt\":\"" + bad + "\"}");
            assertThat(r.getStatus()).as(bad.toString()).isEqualTo(400);
            assertThat(r.getContentAsString()).isEqualTo(msg);
        }
        Instant later = current.plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);
        MockHttpServletResponse ok = change("tp-trial", "{\"trialEndsAt\":\"" + later + "\"}");
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(trialEnd("tp-trial")).isEqualTo(later);
        assertThat(string("SELECT changes::text FROM master.plan_audit_log ORDER BY id DESC LIMIT 1")).contains("trialEndsAt");

        // CANCELED -> a trial extension revives it to TRIALING
        assertThat(change("tp-trial", "{\"subscriptionStatus\":\"CANCELED\"}").getStatus()).isEqualTo(200);
        assertThat(trialEnd("tp-trial")).as("trial end kept as history").isEqualTo(later);
        Instant revive = later.plus(Duration.ofDays(1));
        MockHttpServletResponse revived = change("tp-trial", "{\"trialEndsAt\":\"" + revive + "\"}");
        assertThat(body(revived).get("data").get("subscriptionStatus").asText()).isEqualTo("TRIALING");

        // ACTIVE: no trial extension
        assertThat(change("tp-trial", "{\"subscriptionStatus\":\"ACTIVE\"}").getStatus()).isEqualTo(200);
        MockHttpServletResponse onActive = change("tp-trial", "{\"trialEndsAt\":\"" + revive.plus(Duration.ofDays(1)) + "\"}");
        assertThat(onActive.getStatus()).isEqualTo(400);
        assertThat(body(onActive).get("data").has("trialEndsAt")).isTrue();
        assertThat(string("SELECT subscription_status FROM master.tenants WHERE slug = 'tp-trial'")).isEqualTo("ACTIVE");

        // an expired trial (in the past): the floor is now, not the old end
        fresh("tp-expired", TenantStatus.ACTIVE);
        exec("UPDATE master.tenants SET trial_ends_at = now() - interval '30 days' WHERE slug = 'tp-expired'");
        Instant soon = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
        assertThat(change("tp-expired", "{\"trialEndsAt\":\"" + soon + "\"}").getStatus()).isEqualTo(200);
        // null trial (not provisioned yet): the floor is now as well
        fresh("tp-null", TenantStatus.PENDING);
        assertThat(change("tp-null", "{\"trialEndsAt\":\"" + soon + "\"}").getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ trial clock (section 15)

    @Test
    void provisioningStartsTheTrialOnce_fromThePlansTrialDays_andNeverResetsIt() throws Exception {
        exec("UPDATE master.plans SET trial_days = 30 WHERE id = 1"); // default plan's trial at the moment of provisioning
        String slug = "tp-prov" + SEQ.incrementAndGet();
        ProvisioningRequest req = new ProvisioningRequest(slug, "Prov", "owner@" + slug + ".test", "Owner",
                "$2a$10$" + "a".repeat(53));
        Instant before = Instant.now();
        provisioning.provision(req);
        Instant end = trialEnd(slug);
        assertThat(string("SELECT p.code FROM master.tenants t JOIN master.plans p ON p.id = t.plan_id WHERE t.slug = ?", slug))
                .isEqualTo("STARTER");
        assertThat(end).isBetween(before.plus(Duration.ofDays(30)).minusSeconds(5), Instant.now().plus(Duration.ofDays(30)).plusSeconds(5));
        exec("UPDATE master.plans SET trial_days = 3 WHERE id = 1");
        assertThat(trialEnd(slug)).as("existing trials are never touched by a trial-length change").isEqualTo(end);

        // FAILED -> reprovision: the saga reaches ACTIVE again; the trial end is NOT reset (Security L5)
        exec("UPDATE master.tenants SET status = 'FAILED' WHERE slug = ?", slug);
        exec("DROP SCHEMA IF EXISTS " + "tenant_" + slug.replace('-', '_') + " CASCADE");
        provisioning.provision(req);
        assertThat(string("SELECT status FROM master.tenants WHERE slug = ?", slug)).isEqualTo("ACTIVE");
        assertThat(trialEnd(slug)).isEqualTo(end);
    }

    // ------------------------------------------------------------------------------------------ public list (AC10)

    private static final Set<String> PUBLIC_PLAN_KEYS = Set.of("id", "code", "name", "tagline", "description",
            "monthlyPricePesewas", "annualDiscountPercent", "annualPricePesewas", "annualMonthlyEquivalentPesewas",
            "annualSavingPesewas", "currency", "trialDays", "transactionFeeBps", "isPublic", "isDefaultForSignup",
            "highlighted", "badgeLabel", "bulletsHeading", "marketingBullets", "sortOrder", "active", "updatedAt", "entitlements");

    @Test
    void publicList_isAnonymous_publicActiveOnly_exactPublicKeySet_cacheHeaders_noCookies() throws Exception {
        exec("INSERT INTO master.plans (code, name, monthly_price_pesewas, annual_discount_percent, trial_days, "
                + "transaction_fee_bps, is_public, sort_order, active) VALUES ('SECRET_DEAL', 'Secret', 1, 0, 0, 0, FALSE, 1, TRUE), "
                + "('RETIRED', 'Retired', 1, 0, 0, 0, TRUE, 2, FALSE)");
        exec("UPDATE master.plans SET version = 9, updated_by = 'root@platform.example' WHERE id = 2");
        publicPlanCache.evict();
        // anonymous, and also with a platform cookie / tenant origin: the same public answer, never a cookie
        for (Cookie c : List.of(new Cookie("x", "y"), adminCookie)) {
            MockHttpServletResponse r = call(get(PUBLIC).cookie(c).with(req -> { req.setRemoteAddr("198.51.100.1"); return req; }));
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getHeaders("Set-Cookie")).isEmpty();
            assertThat(r.getCookies()).isEmpty();
            assertThat(r.getHeader("Cache-Control")).isEqualTo("max-age=60, public");
            assertThat(r.getHeaders("Vary")).anyMatch(v -> v.contains("Origin"));
            JsonNode d = body(r).get("data");
            List<String> codes = new ArrayList<>();
            for (JsonNode p : d.get("plans")) {
                codes.add(p.get("code").asText());
                Set<String> keys = new HashSet<>();
                p.fieldNames().forEachRemaining(keys::add);
                assertThat(keys).as("public shape: no version, updatedBy, tenantCounts").isEqualTo(PUBLIC_PLAN_KEYS);
            }
            assertThat(codes).containsExactly("STARTER", "GROWTH", "SCALE");
            assertThat(d.get("features")).hasSize(16);
            String raw = r.getContentAsString();
            assertThat(raw).doesNotContain("root@platform.example").doesNotContain("tenantCounts").doesNotContain("\"version\"")
                    .doesNotContain("SECRET_DEAL").doesNotContain("RETIRED");
        }
        assertThat(call(head(PUBLIC)).getStatus()).isEqualTo(200);
        assertThat(call(post(PUBLIC).contentType("application/json").content("{}")).getStatus()).as("only GET/HEAD are public").isEqualTo(401);
        // zero public plans is a valid empty list
        exec("UPDATE master.plans SET is_public = FALSE");
        publicPlanCache.evict();
        JsonNode empty = body(call(get(PUBLIC))).get("data");
        assertThat(empty.get("plans")).isEmpty();
        assertThat(empty.get("features")).hasSize(16);
    }

    @Test
    void publicList_isServedFromTheShortCache_andACommittedPlanWriteEvictsItAtOnce_aRejectedOneDoesNot() throws Exception {
        assertThat(body(call(get(PUBLIC))).get("data").get("plans").get(1).get("trialDays").asInt()).isEqualTo(14);
        // a write that bypasses the service (no event): the cached answer stays (no master-DB read per hit)
        exec("UPDATE master.plans SET trial_days = 15 WHERE id = 2");
        assertThat(body(call(get(PUBLIC))).get("data").get("plans").get(1).get("trialDays").asInt()).isEqualTo(14);
        // a rejected API write (stale version) evicts nothing
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("version", 7).put("trialDays", 21).toString()).getStatus()).isEqualTo(409);
        assertThat(body(call(get(PUBLIC))).get("data").get("plans").get(1).get("trialDays").asInt()).isEqualTo(14);
        assertThat(EventCapture.COMMITTED).isEmpty();
        // a committed API write evicts after commit: the very next public read shows it, no 60 s wait
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("trialDays", 21).toString()).getStatus()).isEqualTo(200);
        assertThat(body(call(get(PUBLIC))).get("data").get("plans").get(1).get("trialDays").asInt()).isEqualTo(21);
        assertThat(EventCapture.COMMITTED).containsExactly(new PlansChangedEvent(Set.of(2L)));
    }

    @Test
    void publicList_isRateLimitedPerIp() throws Exception {
        String ip = "198.51.100.200";
        for (int i = 0; i < 60; i++) {
            assertThat(call(get(PUBLIC).with(r -> { r.setRemoteAddr(ip); return r; })).getStatus()).as("request " + i).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(get(PUBLIC).with(r -> { r.setRemoteAddr(ip); return r; }));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(body(limited).toString()).isEqualTo("{\"description\":\"Too many requests. Please try again later.\",\"data\":null}");
        assertThat(call(get(PUBLIC).with(r -> { r.setRemoteAddr("198.51.100.201"); return r; })).getStatus()).isEqualTo(200);
    }
}

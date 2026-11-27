package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PLAN-01 on the REAL filter chain and PostgreSQL: authorization of every new route (AC12), the platform plans API
 * (AC5-AC7: create, update, matrix, rules, optimistic lock incl. a real race), validation of every contract bound incl.
 * the Unicode rule (Security requirement 4), and the audit log (AC9, append-only).
 */
class PlatformPlansIntegrationTest extends AbstractPlansIntegrationTest {

    // ------------------------------------------------------------------------------------------ authorization (AC12)

    private List<MockHttpServletRequestBuilder> platformRoutes() {
        return List.of(get(PLANS), post(PLANS).contentType("application/json").content("{}"),
                put(PLANS + "/2").contentType("application/json").content("{}"),
                put(PLANS + "/matrix").contentType("application/json").content("{}"),
                get(PLANS + "/audit"),
                put("/api/v1/platform/tenants/plans-a/plan").contentType("application/json").content("{\"planId\":3}"));
    }

    @Test
    void everyPlatformPlanRoute_anonymous401_tenantTokensOfEveryRole401_nonSuperAdmin403_superAdminPasses() throws Exception {
        TestTenant a = tenants.ensureTenant("plans-a");
        long audit = auditRows();
        for (MockHttpServletRequestBuilder b : platformRoutes()) {
            assertThat(call(b).getStatus()).as("anonymous " + b).isEqualTo(401);
        }
        for (String role : List.of("ADMIN", "CUSTOMER", "FRONT_DESK")) {
            String token = tenants.runAs(a, () -> jwtUtil.generateAccessToken(a.adminEmail(), role, 1L, "Someone"));
            // worst case: the tenant user's e-mail equals the platform admin's
            String sameEmail = tenants.runAs(a, () -> jwtUtil.generateAccessToken(admin.getEmail(), role, 1L, "Someone"));
            for (Cookie c : List.of(new Cookie("accessToken", token), new Cookie("platformAccessToken", token),
                    new Cookie("platformAccessToken", sameEmail))) {
                for (MockHttpServletRequestBuilder b : platformRoutes()) {
                    int s = call(b.cookie(c).header("Authorization", "Bearer " + token)).getStatus();
                    assertThat(s).as(role + " " + c.getName() + " " + b).isEqualTo(401);
                }
            }
        }
        for (MockHttpServletRequestBuilder b : platformRoutes()) {
            assertThat(call(b.with(user("someone").roles("ADMIN"))).getStatus()).as("non-SUPER_ADMIN " + b).isEqualTo(403);
        }
        for (MockHttpServletRequestBuilder b : platformRoutes()) {
            int s = call(b.cookie(adminCookie).header("Origin", CONSOLE)).getStatus();
            assertThat(s).as("SUPER_ADMIN " + b).isNotIn(401, 403);
        }
        // nothing an unauthorised caller sent changed anything (the SUPER_ADMIN bodies above were empty -> 400, or planId 3)
        assertThat(version(2)).isZero();
        assertThat(auditRows()).isEqualTo(audit + 1); // only the SUPER_ADMIN tenant plan change wrote a row
    }

    @Test
    void writesAcceptJsonOnly_andStayBehindThePlatformOriginGuard() throws Exception {
        String body = planBody(2).put("monthlyPricePesewas", 44900).toString();
        MockHttpServletResponse text = call(put(PLANS + "/2").cookie(adminCookie).header("Origin", CONSOLE)
                .contentType("text/plain").content(body));
        assertThat(text.getStatus()).isEqualTo(415);
        MockHttpServletResponse form = call(post(PLANS).cookie(adminCookie).header("Origin", CONSOLE)
                .contentType("application/x-www-form-urlencoded").content("code=EVIL&name=x"));
        assertThat(form.getStatus()).isEqualTo(415);
        for (String origin : List.of("https://evil.example", "https://acme.shopfi.test")) {
            MockHttpServletResponse r = call(put(PLANS + "/2").cookie(adminCookie).header("Origin", origin)
                    .contentType("application/json").content(body));
            assertThat(r.getStatus()).as(origin).isEqualTo(403);
        }
        assertThat(version(2)).isZero();
        // no delete endpoint, no per-plan GET, no PATCH
        assertThat(asAdmin(delete(PLANS + "/2"), null).getStatus()).isEqualTo(405);
        assertThat(asAdmin(patch(PLANS + "/2"), "{}").getStatus()).isEqualTo(405);
        assertThat(asAdmin(get(PLANS + "/2"), null).getStatus()).isIn(404, 405);
    }

    // ------------------------------------------------------------------------------------------ GET list (AC5)

    @Test
    void list_returnsEveryPlanInDisplayOrder_withDerivedPrices_counts_andTheCatalog() throws Exception {
        tenants.ensureTenant("plans-a");
        exec("INSERT INTO master.plans (code, name, monthly_price_pesewas, annual_discount_percent, trial_days, "
                + "transaction_fee_bps, is_public, sort_order, active) VALUES ('HIDDEN_ONE', 'Hidden', 0, 0, 0, 0, FALSE, 5, FALSE)");
        JsonNode d = body(asAdmin(get(PLANS), null)).get("data");
        List<String> codes = new ArrayList<>();
        d.get("plans").forEach(p -> codes.add(p.get("code").asText()));
        assertThat(codes).containsExactly("HIDDEN_ONE", "STARTER", "GROWTH", "SCALE"); // inactive + non-public included
        JsonNode starter = d.get("plans").get(1);
        assertThat(starter.get("annualPricePesewas").asLong()).isEqualTo(143040);
        assertThat(starter.get("annualMonthlyEquivalentPesewas").asLong()).isEqualTo(11920);
        assertThat(starter.get("annualSavingPesewas").asLong()).isEqualTo(35760);
        assertThat(starter.get("version").asLong()).isZero();
        assertThat(starter.get("updatedBy").isNull()).isTrue();
        Set<String> countKeys = new HashSet<>();
        starter.get("tenantCounts").fieldNames().forEachRemaining(countKeys::add);
        assertThat(countKeys).containsExactlyInAnyOrder("TRIALING", "ACTIVE", "PAST_DUE", "CANCELED");
        assertThat(starter.get("tenantCounts").get("TRIALING").asLong())
                .isEqualTo(scalar("SELECT count(*) FROM master.tenants WHERE plan_id = 1 AND subscription_status = 'TRIALING'"));
        assertThat(d.get("features")).hasSize(16);
        assertThat(starter.get("entitlements")).hasSize(16);
        assertThat(d.get("plans").get(0).get("tenantCounts").get("ACTIVE").asLong()).isZero();
        assertThat(auditRows()).as("reads write no audit row").isEqualTo(scalar("SELECT count(*) FROM master.plan_audit_log"));
    }

    // ------------------------------------------------------------------------------------------ create (AC5)

    private ObjectNode createBody(String code) throws Exception {
        ObjectNode o = planBody(3).fieldsOnly();
        o.put("code", code);
        o.put("name", "Enterprise");
        o.put("isDefaultForSignup", false);
        o.put("highlighted", false);
        o.put("isPublic", false);
        o.put("sortOrder", 40);
        return o;
    }

    @Test
    void create_answers201_version0_entitlementsAllOff_countsZero_auditedWithActorIdAndEmail() throws Exception {
        long before = auditRows();
        ObjectNode b = createBody("ENTERPRISE");
        b.put("monthlyPricePesewas", 249900).put("annualDiscountPercent", 25).put("trialDays", 30).put("transactionFeeBps", 40);
        b.put("tagline", "   ");                     // blank optional text -> null
        b.put("description", "  Custom contracts for large brands.  "); // trimmed
        b.put("annualPricePesewas", 1).put("currency", "USD").put("id", 999).put("version", 77); // read-only: ignored
        b.set("entitlements", JSON.readTree("[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true,\"limitValue\":null}]"));
        MockHttpServletResponse r = asAdmin(post(PLANS), b.toString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        JsonNode p = body(r).get("data");
        assertThat(p.get("id").asLong()).isGreaterThan(3).isNotEqualTo(999);
        assertThat(p.get("code").asText()).isEqualTo("ENTERPRISE");
        assertThat(p.get("version").asLong()).isZero();
        assertThat(p.get("currency").asText()).isEqualTo("GHS");
        assertThat(p.get("tagline").isNull()).isTrue();
        assertThat(p.get("description").asText()).isEqualTo("Custom contracts for large brands.");
        assertThat(p.get("annualPricePesewas").asLong()).isEqualTo(2249100);
        assertThat(p.get("annualMonthlyEquivalentPesewas").asLong()).isEqualTo(187425);
        assertThat(p.get("annualSavingPesewas").asLong()).isEqualTo(749700);
        assertThat(p.get("updatedBy").asText()).isEqualTo(admin.getEmail());
        for (JsonNode e : p.get("entitlements")) {
            assertThat(e.get("enabled").asBoolean()).as(e.toString()).isFalse();
            if (e.get("limitValue").isNull()) {
                assertThat(PlanFeature.fromKey(e.get("featureKey").asText()).orElseThrow().kind()).isEqualTo(FeatureKind.BOOLEAN);
            } else {
                assertThat(e.get("limitValue").asInt()).isZero();
            }
        }
        for (String s : List.of("TRIALING", "ACTIVE", "PAST_DUE", "CANCELED")) {
            assertThat(p.get("tenantCounts").get(s).asLong()).isZero();
        }
        long id = p.get("id").asLong();
        assertThat(auditRows()).isEqualTo(before + 1);
        assertThat(string("SELECT action FROM master.plan_audit_log WHERE plan_id = ? ORDER BY id DESC LIMIT 1", id)).isEqualTo("PLAN_CREATE");
        assertThat(scalar("SELECT actor_admin_id FROM master.plan_audit_log WHERE plan_id = ? ORDER BY id DESC LIMIT 1", id)).isEqualTo(admin.getId());
        assertThat(string("SELECT actor_email FROM master.plan_audit_log WHERE plan_id = ? ORDER BY id DESC LIMIT 1", id)).isEqualTo(admin.getEmail());
        assertThat(scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = ?", id)).isEqualTo(16);
        assertThat(EventCapture.COMMITTED).containsExactly(new PlansChangedEvent(Set.of(id)));

        // the same code again: 409 PLAN_CODE_TAKEN, nothing written
        MockHttpServletResponse taken = asAdmin(post(PLANS), createBody("ENTERPRISE").toString());
        assertThat(taken.getStatus()).isEqualTo(409);
        assertThat(body(taken).get("data").get("code").asText()).isEqualTo("PLAN_CODE_TAKEN");
        assertThat(body(taken).get("description").asText()).isEqualTo("A plan with this code already exists");
        assertThat(auditRows()).isEqualTo(before + 1);
    }

    @Test
    void createAsDefault_movesTheFlag_bumpsTheOldDefault_andAuditsBoth() throws Exception {
        ObjectNode b = createBody("LAUNCH");
        b.put("isDefaultForSignup", true).put("trialDays", 7);
        MockHttpServletResponse r = asAdmin(post(PLANS), b.toString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        long id = body(r).get("data").get("id").asLong();
        assertThat(scalar("SELECT count(*) FROM master.plans WHERE is_default_for_signup")).isEqualTo(1);
        assertThat(scalar("SELECT id FROM master.plans WHERE is_default_for_signup")).isEqualTo(id);
        assertThat(version(1)).as("STARTER lost the flag: own version bump").isEqualTo(1);
        assertThat(string("SELECT changes::text FROM master.plan_audit_log WHERE plan_id = 1 ORDER BY id DESC LIMIT 1"))
                .contains("isDefaultForSignup").contains("true").contains("false");
        // a non-public default is allowed (an unlisted launch plan)
        assertThat(body(asAdmin(get(PUBLIC), null)).get("data").get("plans")).hasSize(3);
    }

    // ------------------------------------------------------------------------------------------ update (AC5, AC7)

    @Test
    void update_recomputesDerivedPrices_bumpsVersion_noOpWritesNothing_staleIs409_unknownIs404_codeIsImmutable() throws Exception {
        long before = auditRows();
        MockHttpServletResponse r = asAdmin(put(PLANS + "/2"),
                planBody(2).put("monthlyPricePesewas", 44900).put("annualDiscountPercent", 15).toString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode p = body(r).get("data");
        assertThat(p.get("version").asLong()).isEqualTo(1);
        assertThat(p.get("annualPricePesewas").asLong()).isEqualTo(457980);
        assertThat(p.get("annualMonthlyEquivalentPesewas").asLong()).isEqualTo(38165);
        assertThat(p.get("annualSavingPesewas").asLong()).isEqualTo(80820);
        assertThat(auditRows()).isEqualTo(before + 1);
        JsonNode changes = JSON.readTree(string("SELECT changes::text FROM master.plan_audit_log ORDER BY id DESC LIMIT 1"));
        assertThat(changes).isEqualTo(JSON.readTree("[{\"field\":\"monthlyPricePesewas\",\"before\":39900,\"after\":44900},"
                + "{\"field\":\"annualDiscountPercent\",\"before\":20,\"after\":15}]"));

        // the same body again (version 1): equal values, nothing written, no bump
        MockHttpServletResponse same = asAdmin(put(PLANS + "/2"), planBody(2).toString());
        assertThat(same.getStatus()).isEqualTo(200);
        assertThat(body(same).get("data").get("version").asLong()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(before + 1);

        // stale version: 409 with the conflict, nothing written
        MockHttpServletResponse stale = asAdmin(put(PLANS + "/2"), planBody(2).put("version", 0).put("trialDays", 21).toString());
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(body(stale).get("data").toString())
                .isEqualTo("{\"code\":\"PLAN_VERSION_CONFLICT\",\"conflicts\":[{\"planId\":2,\"expectedVersion\":0,\"currentVersion\":1}]}");
        assertThat(scalar("SELECT trial_days FROM master.plans WHERE id = 2")).isEqualTo(14);

        MockHttpServletResponse unknown = asAdmin(put(PLANS + "/9999"), planBody(2).toString());
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(body(unknown).toString()).isEqualTo("{\"description\":\"Plan not found\",\"data\":{\"code\":\"PLAN_NOT_FOUND\"}}");

        MockHttpServletResponse code = asAdmin(put(PLANS + "/2"), planBody(2).put("code", "GROWTH_X").toString());
        assertThat(code.getStatus()).isEqualTo(400);
        assertThat(body(code).get("data").get("code").asText()).isEqualTo("code cannot be changed");
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("code", "GROWTH").toString()).getStatus()).isEqualTo(200);
        assertThat(string("SELECT code FROM master.plans WHERE id = 2")).isEqualTo("GROWTH");
    }

    /** AC7: two writers from the same version, exactly one wins; the other gets the dedicated 409 (never 503). */
    @Test
    void twoConcurrentWritersFromTheSameVersion_exactlyOneWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            String a = planBody(2).put("trialDays", 20 + round).toString();
            String b = planBody(2).put("trialDays", 40 + round).toString();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (String body : List.of(a, b)) {
                    Callable<Integer> task = () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return asAdmin(put(PLANS + "/2"), body).getStatus();
                    };
                    results.add(pool.submit(task));
                }
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) {
                    statuses.add(f.get(60, TimeUnit.SECONDS));
                }
                assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            } finally {
                pool.shutdownNow();
            }
            assertThat(version(2)).isEqualTo(round + 1);
        }
    }

    // ------------------------------------------------------------------------------------------ validation (Security 4)

    private void expect400(long id, String field, Object value, String errorKey) throws Exception {
        AbstractPlansIntegrationTest.ObjectNodeBuilder b = planBody(id);
        if (value instanceof JsonNode n) {
            b.raw(field, n);
        } else if (value == REMOVE) {
            b.remove(field);
        } else {
            b.put(field, value);
        }
        MockHttpServletResponse r = asAdmin(put(PLANS + "/" + id), b.toString());
        assertThat(r.getStatus()).as(field + "=" + value + ": " + r.getContentAsString()).isEqualTo(400);
        JsonNode body = body(r);
        if (errorKey != null) {
            assertThat(body.get("description").asText()).isEqualTo("Validation failed");
            assertThat(body.get("data").has(errorKey)).as(field + " -> " + body).isTrue();
        }
    }

    private static final Object REMOVE = new Object();

    @Test
    void everyContractBoundIsEnforcedServerSide() throws Exception {
        long before = auditRows();
        expect400(2, "name", "  ", "name");
        expect400(2, "name", "x".repeat(41), "name");
        expect400(2, "name", REMOVE, "name");
        expect400(2, "tagline", "x".repeat(41), "tagline");
        expect400(2, "description", "x".repeat(281), "description");
        expect400(2, "badgeLabel", "x".repeat(41), "badgeLabel");
        expect400(2, "bulletsHeading", "x".repeat(81), "bulletsHeading");
        expect400(2, "monthlyPricePesewas", -1, "monthlyPricePesewas");
        expect400(2, "monthlyPricePesewas", 100_000_001, "monthlyPricePesewas");
        expect400(2, "monthlyPricePesewas", JSON.readTree("149.5"), null);   // fraction: never truncated
        expect400(2, "monthlyPricePesewas", JSON.readTree("\"39900\""), null); // text: not a JSON integer
        expect400(2, "monthlyPricePesewas", JSON.readTree("1E999999999"), null);
        expect400(2, "annualDiscountPercent", 101, "annualDiscountPercent");
        expect400(2, "annualDiscountPercent", -1, "annualDiscountPercent");
        expect400(2, "annualDiscountPercent", JSON.readTree("20.5"), null);
        expect400(2, "trialDays", 366, "trialDays");
        expect400(2, "trialDays", -1, "trialDays");
        expect400(2, "transactionFeeBps", 1001, "transactionFeeBps");
        expect400(2, "sortOrder", 1001, "sortOrder");
        expect400(2, "sortOrder", -1, "sortOrder");
        expect400(2, "isPublic", REMOVE, "isPublic");
        expect400(2, "active", JSON.nullNode(), "active");
        expect400(2, "version", REMOVE, "version");
        expect400(2, "version", -1, "version");
        expect400(2, "marketingBullets", REMOVE, "marketingBullets");
        List<String> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            thirteen.add("Bullet " + i);
        }
        expect400(2, "marketingBullets", thirteen, "marketingBullets");
        expect400(2, "marketingBullets", List.of("ok", "x".repeat(121)), "marketingBullets[1]");
        expect400(2, "marketingBullets", List.of("   "), "marketingBullets[0]");
        // the plain-text rule: Cc, Cf, U+2028 / U+2029 anywhere, in every text field (Security L2)
        expect400(2, "marketingBullets", List.of("Only GHS ‮009 a month"), "marketingBullets[0]");
        expect400(2, "name", "Gro​wth", "name");
        expect400(2, "name", "\tGrowth", "name");                      // a leading control is refused, not trimmed away
        expect400(2, "name", "Growth ", "name");
        expect400(2, "tagline", "FOR SELLERS", "tagline");
        expect400(2, "description", "Full \u0085brand", "description"); // C1 control
        expect400(2, "badgeLabel", "﻿Most popular", "badgeLabel");
        expect400(2, "bulletsHeading", "Plus⁦:", "bulletsHeading");
        expect400(2, "badgeLabel", "Tag󠀁", "badgeLabel");     // astral Cf U+E0001
        expect400(2, "name", "Soft­hyphen", "name");
        assertThat(version(2)).as("nothing written by any rejected request").isZero();
        assertThat(auditRows()).isEqualTo(before);
        // boundaries that ARE allowed
        MockHttpServletResponse max = asAdmin(put(PLANS + "/2"), planBody(2).put("name", "x".repeat(40))
                .put("monthlyPricePesewas", 100_000_000).put("annualDiscountPercent", 1).put("trialDays", 365)
                .put("transactionFeeBps", 1000).put("sortOrder", 1000).put("tagline", "y".repeat(40))
                .put("description", "z".repeat(280)).put("badgeLabel", "b".repeat(40)).put("bulletsHeading", "h".repeat(80))
                .put("marketingBullets", List.of("é中🚀 " + "w".repeat(115), "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"))
                .toString());
        assertThat(max.getStatus()).as(max.getContentAsString()).isEqualTo(200);
        JsonNode p = body(max).get("data");
        assertThat(p.get("annualPricePesewas").asLong()).isEqualTo(1_188_000_000L);   // derived at the bound (no int overflow)
        assertThat(p.get("annualMonthlyEquivalentPesewas").asLong()).isEqualTo(99_000_000L);
        assertThat(p.get("annualSavingPesewas").asLong()).isEqualTo(12_000_000L);
        for (JsonNode listed : body(asAdmin(get(PLANS), null)).get("data").get("plans")) {
            if (listed.get("id").asLong() == 2) {
                assertThat(listed.get("annualPricePesewas").asLong()).isEqualTo(1_188_000_000L);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ rules (AC6)

    @Test
    void planRules_areCheckedOnTheFinalState_withTheFieldNamed() throws Exception {
        long before = auditRows();
        MockHttpServletResponse deactivateDefault = asAdmin(put(PLANS + "/1"), planBody(1).put("active", false).toString());
        assertThat(body(deactivateDefault).toString())
                .isEqualTo("{\"description\":\"Validation failed\",\"data\":{\"active\":\"the default sign-up plan cannot be deactivated\"}}");
        MockHttpServletResponse unsetDefault = asAdmin(put(PLANS + "/1"), planBody(1).put("isDefaultForSignup", false).toString());
        assertThat(body(unsetDefault).get("data").get("isDefaultForSignup").asText()).isEqualTo("another plan must become the default first");
        MockHttpServletResponse zeroTrial = asAdmin(put(PLANS + "/1"), planBody(1).put("trialDays", 0).toString());
        assertThat(body(zeroTrial).toString())
                .isEqualTo("{\"description\":\"Validation failed\",\"data\":{\"trialDays\":\"the default sign-up plan needs a trial of at least 1 day\"}}");
        exec("UPDATE master.plans SET active = FALSE WHERE id = 3");
        MockHttpServletResponse inactiveDefault = asAdmin(put(PLANS + "/3"), planBody(3).put("isDefaultForSignup", true).toString());
        assertThat(body(inactiveDefault).get("data").get("isDefaultForSignup").asText()).isEqualTo("an inactive plan cannot be the default");
        exec("UPDATE master.plans SET is_public = FALSE WHERE id = 2");
        MockHttpServletResponse lastPublic = asAdmin(put(PLANS + "/1"), planBody(1).put("isPublic", false).toString());
        assertThat(body(lastPublic).get("data").get("isPublic").asText()).isEqualTo("at least one public active plan must remain");
        for (MockHttpServletResponse r : List.of(deactivateDefault, unsetDefault, zeroTrial, inactiveDefault, lastPublic)) {
            assertThat(r.getStatus()).isEqualTo(400);
        }
        assertThat(auditRows()).isEqualTo(before);
        assertThat(version(1)).isZero();

        // non-public or inactive plans leave the public list at once; tenants on them keep their plan
        exec("UPDATE master.plans SET is_public = TRUE WHERE id = 2");
        exec("UPDATE master.plans SET active = TRUE WHERE id = 3");
        assertThat(asAdmin(put(PLANS + "/3"), planBody(3).put("active", false).toString()).getStatus()).isEqualTo(200);
        publicPlanCache.evict();
        List<String> pub = new ArrayList<>();
        body(call(get(PUBLIC))).get("data").get("plans").forEach(p -> pub.add(p.get("code").asText()));
        assertThat(pub).containsExactly("STARTER", "GROWTH");
    }

    @Test
    void movingTheDefaultFlag_clearsTheOldDefault_inTheSameTransaction() throws Exception {
        long lastId = maxAuditId();
        MockHttpServletResponse r = asAdmin(put(PLANS + "/2"), planBody(2).put("isDefaultForSignup", true).toString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(scalar("SELECT id FROM master.plans WHERE is_default_for_signup")).isEqualTo(2);
        assertThat(version(1)).isEqualTo(1);
        assertThat(version(2)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM master.plan_audit_log WHERE plan_id IN (1, 2) AND action = 'PLAN_UPDATE' AND id > ?", lastId)).isEqualTo(2);
        assertThat(EventCapture.COMMITTED).hasSize(1);
        assertThat(((PlansChangedEvent) EventCapture.COMMITTED.get(0)).planIds()).containsExactlyInAnyOrder(1L, 2L);
        // the old default's holder now gets a 409 on its next write of it (section 9 note)
        MockHttpServletResponse stale = asAdmin(put(PLANS + "/1"), planBody(1).put("version", 0).toString());
        assertThat(stale.getStatus()).isEqualTo(409);
    }

    // ------------------------------------------------------------------------------------------ matrix (AC5, AC7)

    private String matrixItem(long id, long version, String entitlements, ObjectNode plan) {
        ObjectNode o = JSON.createObjectNode();
        o.put("id", id).put("version", version);
        try {
            if (entitlements != null) {
                o.set("entitlements", JSON.readTree(entitlements));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (plan != null) {
            o.set("plan", plan);
        }
        return o.toString();
    }

    private MockHttpServletResponse matrix(String... items) throws Exception {
        return asAdmin(put(PLANS + "/matrix"), "{\"plans\":[" + String.join(",", items) + "]}");
    }

    @Test
    void matrix_updatesSeveralPlansInOneTransaction_answersTheWholeCatalog_andAuditsEachChangedPlan() throws Exception {
        long before = auditRows();
        long lastId = maxAuditId();
        MockHttpServletResponse r = matrix(
                matrixItem(2, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]", null),
                matrixItem(1, 0, "[{\"featureKey\":\"MAX_PRODUCTS\",\"limitValue\":100},{\"featureKey\":\"MAX_STAFF\",\"limitValue\":null}]",
                        planBody(1).put("trialDays", 30).fieldsOnly()),
                matrixItem(3, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true}]", null)); // equal: no write
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("plans")).hasSize(3);
        assertThat(d.get("features")).hasSize(16);
        assertThat(version(1)).isEqualTo(1);
        assertThat(version(2)).isEqualTo(1);
        assertThat(version(3)).as("unchanged item: version-checked, nothing written").isZero();
        assertThat(scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = 2 AND feature_key = 'WALK_IN_POS' AND NOT enabled")).isEqualTo(1);
        assertThat(scalar("SELECT limit_value FROM master.plan_entitlements WHERE plan_id = 1 AND feature_key = 'MAX_PRODUCTS'")).isEqualTo(100);
        assertThat(scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = 1 AND feature_key = 'MAX_STAFF' AND limit_value IS NULL AND enabled")).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(before + 2);
        assertThat(scalar("SELECT count(*) FROM master.plan_audit_log WHERE action = 'PLAN_MATRIX_UPDATE' AND id > ?", lastId)).isEqualTo(2);
        JsonNode starterChanges = JSON.readTree(string("SELECT changes::text FROM master.plan_audit_log WHERE plan_id = 1 ORDER BY id DESC LIMIT 1"));
        assertThat(starterChanges).isEqualTo(JSON.readTree("[{\"field\":\"trialDays\",\"before\":14,\"after\":30},"
                + "{\"field\":\"entitlements.MAX_PRODUCTS\",\"before\":50,\"after\":100},"
                + "{\"field\":\"entitlements.MAX_STAFF\",\"before\":0,\"after\":null}]"));
        JsonNode growth = d.get("plans").get(1);
        for (JsonNode e : growth.get("entitlements")) {
            if (e.get("featureKey").asText().equals("WALK_IN_POS")) {
                assertThat(e.get("enabled").asBoolean()).isFalse();
            }
        }
        assertThat(EventCapture.COMMITTED).containsExactly(new PlansChangedEvent(Set.of(1L, 2L)));
    }

    @Test
    void matrix_isAllOrNothing_staleItemLists409_invalidItemIs400_unknownIdIs404() throws Exception {
        long before = auditRows();
        exec("UPDATE master.plans SET version = 5 WHERE id = 3");
        exec("UPDATE master.plans SET version = 2 WHERE id = 1");
        MockHttpServletResponse stale = matrix(
                matrixItem(2, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]", null),
                matrixItem(3, 4, "[{\"featureKey\":\"ACCOUNTING\",\"enabled\":false}]", null),
                matrixItem(1, 0, "[{\"featureKey\":\"ACCOUNTING\",\"enabled\":true}]", null));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(body(stale).get("data").get("conflicts").toString())
                .isEqualTo("[{\"planId\":3,\"expectedVersion\":4,\"currentVersion\":5},{\"planId\":1,\"expectedVersion\":0,\"currentVersion\":2}]");
        assertThat(body(stale).get("description").asText())
                .isEqualTo("Another admin changed these plans. Reload them and apply your changes again.");

        String okItem = matrixItem(2, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]", null);
        MockHttpServletResponse unknownKey = matrix(okItem, matrixItem(3, 5, "[{\"featureKey\":\"MAX_CUSTOM_DOMAINS\",\"limitValue\":1}]", null));
        assertThat(body(unknownKey).toString()).isEqualTo(
                "{\"description\":\"Validation failed\",\"data\":{\"plans[1].entitlements[0].featureKey\":\"unknown feature\"}}");
        MockHttpServletResponse wrongKind = matrix(matrixItem(2, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true,\"limitValue\":3}]", null));
        assertThat(body(wrongKind).toString()).isEqualTo(
                "{\"description\":\"Validation failed\",\"data\":{\"plans[0].entitlements[0].limitValue\":\"must be null for a BOOLEAN feature\"}}");
        MockHttpServletResponse limitAbsent = matrix(matrixItem(2, 0, "[{\"featureKey\":\"MAX_PRODUCTS\",\"enabled\":false}]", null));
        assertThat(body(limitAbsent).get("data").has("plans[0].entitlements[0].limitValue"))
                .as("an absent limit is never read as unlimited").isTrue();
        MockHttpServletResponse dupKey = matrix(matrixItem(2, 0, "[{\"featureKey\":\"ACCOUNTING\",\"enabled\":true},{\"featureKey\":\"ACCOUNTING\",\"enabled\":false}]", null));
        assertThat(body(dupKey).get("data").get("plans[0].entitlements[1].featureKey").asText()).isEqualTo("duplicate feature");
        MockHttpServletResponse negative = matrix(matrixItem(1, 2, "[{\"featureKey\":\"MAX_PRODUCTS\",\"limitValue\":-1}]", null));
        assertThat(body(negative).get("data").has("plans[0].entitlements[0].limitValue")).isTrue();
        MockHttpServletResponse ruleBreak = matrix(okItem, matrixItem(1, 2, null, planBody(1).put("active", false).fieldsOnly()));
        assertThat(body(ruleBreak).get("data").get("plans[1].plan.active").asText()).isEqualTo("the default sign-up plan cannot be deactivated");
        MockHttpServletResponse twoDefaults = matrix(matrixItem(2, 0, null, planBody(2).put("isDefaultForSignup", true).fieldsOnly()),
                matrixItem(3, 5, null, planBody(3).put("isDefaultForSignup", true).fieldsOnly()));
        assertThat(body(twoDefaults).get("data").get("plans[1].plan.isDefaultForSignup").asText()).isEqualTo("only one plan can be the default");
        MockHttpServletResponse dupId = matrix(okItem, okItem);
        assertThat(body(dupId).get("data").get("plans[1].id").asText()).isEqualTo("duplicate plan id");
        MockHttpServletResponse noChange = matrix("{\"id\":2,\"version\":0}");
        assertThat(body(noChange).get("data").has("plans[0]")).isTrue();
        for (MockHttpServletResponse x : List.of(unknownKey, wrongKind, limitAbsent, dupKey, negative, ruleBreak, twoDefaults, dupId, noChange)) {
            assertThat(x.getStatus()).as(x.getContentAsString()).isEqualTo(400);
        }
        MockHttpServletResponse unknownId = matrix(okItem, matrixItem(9999, 0, "[{\"featureKey\":\"ACCOUNTING\",\"enabled\":true}]", null));
        assertThat(unknownId.getStatus()).isEqualTo(404);
        assertThat(body(unknownId).get("data").get("code").asText()).isEqualTo("PLAN_NOT_FOUND");
        assertThat(asAdmin(put(PLANS + "/matrix"), "{\"plans\":[]}").getStatus()).isEqualTo(400);
        StringBuilder many = new StringBuilder("[");
        for (PlanFeature f : PlanFeature.values()) {
            many.append(f.kind() == FeatureKind.BOOLEAN ? "{\"featureKey\":\"" + f + "\",\"enabled\":true}," : "{\"featureKey\":\"" + f + "\",\"limitValue\":1},");
        }
        many.append("{\"featureKey\":\"ACCOUNTING\",\"enabled\":true}]");
        assertThat(matrix(matrixItem(2, 0, many.toString(), null)).getStatus()).as("17 entitlements").isEqualTo(400);

        // nothing at all was written by any of these
        assertThat(version(2)).isZero();
        assertThat(version(1)).isEqualTo(2);
        assertThat(version(3)).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = 2 AND feature_key = 'WALK_IN_POS' AND enabled")).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(before);
        assertThat(EventCapture.COMMITTED).as("no committed write, no eviction event").isEmpty();
    }

    /** plans.md section 8 check order: 400 field validation -> 404 unknown id -> 409 stale version -> 400 plan rules. */
    @Test
    void matrix_checkOrder_fieldValidation_thenUnknownId_thenStaleVersion_thenPlanRules() throws Exception {
        String stale = matrixItem(2, 9, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]", null);
        String unknownId = matrixItem(9999, 0, "[{\"featureKey\":\"ACCOUNTING\",\"enabled\":true}]", null);
        String badContent = matrixItem(3, 9, "[{\"featureKey\":\"NOT_A_FEATURE\",\"enabled\":true}]", null);
        String ruleBreak = matrixItem(1, 0, null, planBody(1).put("active", false).fieldsOnly());
        // field validation beats an unknown id and a stale version
        MockHttpServletResponse r1 = matrix(stale, unknownId, badContent);
        assertThat(r1.getStatus()).isEqualTo(400);
        assertThat(body(r1).get("data").get("plans[2].entitlements[0].featureKey").asText()).isEqualTo("unknown feature");
        // an unknown id beats a stale version
        MockHttpServletResponse r2 = matrix(stale, unknownId);
        assertThat(r2.getStatus()).isEqualTo(404);
        assertThat(body(r2).get("data").get("code").asText()).isEqualTo("PLAN_NOT_FOUND");
        // a stale version beats a plan-rule violation
        MockHttpServletResponse r3 = matrix(stale, ruleBreak);
        assertThat(r3.getStatus()).isEqualTo(409);
        assertThat(body(r3).get("data").get("code").asText()).isEqualTo("PLAN_VERSION_CONFLICT");
        // only then the rules
        MockHttpServletResponse r4 = matrix(ruleBreak);
        assertThat(r4.getStatus()).isEqualTo(400);
        assertThat(body(r4).get("data").get("plans[0].plan.active").asText()).isEqualTo("the default sign-up plan cannot be deactivated");
        assertThat(version(1) + version(2) + version(3)).isZero();
    }

    // ------------------------------------------------------------------------------------------ audit log (AC9)

    @Test
    void auditPage_isNewestFirst_filtered_paged_withExactlyTheContractKeys() throws Exception {
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("trialDays", 10).toString()).getStatus()).isEqualTo(200);
        assertThat(asAdmin(put(PLANS + "/3"), planBody(3).put("trialDays", 11).toString()).getStatus()).isEqualTo(200);
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("trialDays", 12).toString()).getStatus()).isEqualTo(200);
        JsonNode page = body(asAdmin(get(PLANS + "/audit?size=2"), null)).get("data");
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asLong()).isGreaterThan(page.get("items").get(1).get("id").asLong());
        JsonNode row = page.get("items").get(0);
        Set<String> keys = new HashSet<>();
        row.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("id", "occurredAt", "actorEmail", "action", "planId", "planCode", "tenantSlug", "changes");
        assertThat(row.get("planCode").asText()).isEqualTo("GROWTH");
        assertThat(row.get("actorEmail").asText()).isEqualTo(admin.getEmail());
        assertThat(row.get("occurredAt").asText()).endsWith("Z");
        assertThat(row.toString()).doesNotContain("actorAdminId").doesNotContain("tenantId");
        JsonNode onlyScale = body(asAdmin(get(PLANS + "/audit?planId=3"), null)).get("data").get("items");
        onlyScale.forEach(i -> assertThat(i.get("planId").asLong()).isEqualTo(3));
        assertThat(body(asAdmin(get(PLANS + "/audit?size=1000"), null)).get("data").get("size").asInt()).isEqualTo(100);
        for (String bad : List.of("page=-1", "page=1000001", "size=0", "planId=abc", "planId=0", "tenantSlug=Bad_Slug", "page=x")) {
            assertThat(asAdmin(get(PLANS + "/audit?" + bad), null).getStatus()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void auditTableIsAppendOnly_inSqlAndInCode() throws Exception {
        assertThat(asAdmin(put(PLANS + "/2"), planBody(2).put("trialDays", 10).toString()).getStatus()).isEqualTo(200);
        long n = auditRows();
        for (String sql : List.of("UPDATE master.plan_audit_log SET actor_email = 'x@y.z'",
                "DELETE FROM master.plan_audit_log", "TRUNCATE master.plan_audit_log")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(sql)).as(sql).hasMessageContaining("append-only");
        }
        assertThat(auditRows()).isEqualTo(n);
        // the repository exposes no update or delete path
        for (java.lang.reflect.Method m : com.example.perfume_budget.platform.plan.repository.PlanAuditLogRepository.class.getMethods()) {
            assertThat(m.getName()).doesNotStartWith("delete").doesNotStartWith("update").doesNotStartWith("remove");
        }
        assertThat(com.example.perfume_budget.platform.plan.model.PlanAuditLog.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
    }

    /**
     * AC9 / Security requirement 11: the audit row is written IN the write's transaction. An injected failure of the
     * audit insert rolls the plan change back (and no eviction event is delivered).
     */
    @Test
    void aFailingAuditInsertRollsThePlanWriteBack() throws Exception {
        exec("CREATE OR REPLACE FUNCTION master.plan01_audit_fail() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER plan01_audit_fail BEFORE INSERT ON master.plan_audit_log FOR EACH ROW EXECUTE FUNCTION master.plan01_audit_fail()");
        try {
            MockHttpServletResponse r = asAdmin(put(PLANS + "/2"), planBody(2).put("trialDays", 21).toString());
            assertThat(r.getStatus()).isGreaterThanOrEqualTo(500);
            MockHttpServletResponse m = matrix(matrixItem(2, 0, "[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]", null));
            assertThat(m.getStatus()).isGreaterThanOrEqualTo(500);
            tenants.ensureTenant("plans-a");
            MockHttpServletResponse t = asAdmin(put("/api/v1/platform/tenants/plans-a/plan"), "{\"subscriptionStatus\":\"CANCELED\"}");
            assertThat(t.getStatus()).isGreaterThanOrEqualTo(500);
        } finally {
            exec("DROP TRIGGER IF EXISTS plan01_audit_fail ON master.plan_audit_log");
            exec("DROP FUNCTION IF EXISTS master.plan01_audit_fail()");
        }
        assertThat(version(2)).isZero();
        assertThat(scalar("SELECT trial_days FROM master.plans WHERE id = 2")).isEqualTo(14);
        assertThat(scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = 2 AND feature_key = 'WALK_IN_POS' AND enabled")).isEqualTo(1);
        assertThat(string("SELECT subscription_status FROM master.tenants WHERE slug = 'plans-a'")).isNotEqualTo("CANCELED");
        assertThat(EventCapture.COMMITTED).isEmpty();
    }
}

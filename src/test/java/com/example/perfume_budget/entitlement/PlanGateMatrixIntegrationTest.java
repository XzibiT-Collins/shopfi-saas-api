package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.TestPlans;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-02 AC2/AC3/AC6/AC10 and plans.md 14 / 18 items 8 and 10: every gated route of the registry (the
 * {@code @RequiresFeature} scan) x every seeded plan answers as the seed matrix says; read-only (CANCELED, expired trial at
 * expiry-1s / expiry / expiry+1s on a fixed clock) blocks gated writes and limit entries but not reads; a FRONT_DESK user
 * without the role / permission always gets 403, never a 402; and every route WITHOUT the marker never answers 402,
 * even for a tenant on a plan with nothing that is CANCELED.
 */
class PlanGateMatrixIntegrationTest extends AbstractEntitlementIntegrationTest {

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    /** The registry as the running application sees it: Controller#method -> features of its markers. */
    private Map<String, Set<String>> registry() {
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            for (RequiresFeature rf : AnnotatedElementUtils.findMergedRepeatableAnnotations(h.getMethod(), RequiresFeature.class)) {
                out.computeIfAbsent(h.getBeanType().getSimpleName() + "#" + h.getMethod().getName(), k -> new TreeSet<>())
                        .add(rf.value().name() + "/" + rf.mode());
            }
        }
        return out;
    }

    @Test
    void everyRegistryEntryHasExactlyOneTestRow_andEveryRowIsARegistryEntry() {
        Set<String> annotated = new TreeSet<>();
        registry().forEach((h, fs) -> fs.forEach(f -> annotated.add(h + " " + f)));
        Set<String> tested = new TreeSet<>();
        for (Row r : rows()) {
            assertThat(tested.add(r.handler() + " " + r.feature() + "/" + r.mode())).as("duplicate row " + r.handler()).isTrue();
        }
        assertThat(tested).as("a new @RequiresFeature needs a row in AbstractEntitlementIntegrationTest#rows").isEqualTo(annotated);
        assertThat(annotated).hasSize(41);
    }

    @Test
    void everyGatedRoute_onEverySeededPlan_answersAsTheSeedMatrixSays() throws Exception {
        T t = tenant("p2-matrix");
        List<String> problems = new ArrayList<>();
        int cells = 0;
        for (long plan : new long[]{TestPlans.STARTER_ID, TestPlans.GROWTH_ID, TestPlans.SCALE_ID}) {
            setPlan(t, plan, "TRIALING", Instant.now().plus(5, ChronoUnit.DAYS));
            for (Row row : rows()) {
                String what = code(plan) + " " + row.handler() + " " + row.feature();
                Integer limit = null;
                boolean allowed;
                if (row.mode() == RequiresFeature.Mode.LIMIT) {
                    limit = limit(plan, row.feature());
                    if (limit != null) {
                        fillTo(t, row.feature(), limit);
                    }
                    allowed = limit == null;
                } else {
                    allowed = enabled(plan, row.feature());
                }
                MockHttpServletResponse r = send(t, t.admin(), row);
                cells++;
                try {
                    if (allowed) {
                        assertPassedTheGate(r, what);
                    } else if (row.mode() == RequiresFeature.Mode.LIMIT) {
                        assert402(r, "PLAN_LIMIT_REACHED", row.feature(), null, what);
                        assertThat(body(r).at("/data/limit").asInt()).as(what).isEqualTo(limit);
                        assertThat(body(r).at("/data/used").asLong()).as(what).isEqualTo(count(t, row.feature()));
                        assertThat(body(r).get("description").asText()).isEqualTo("You have reached your plan's limit for this feature.");
                    } else {
                        assert402(r, "PLAN_UPGRADE_REQUIRED", row.feature(), "NOT_IN_PLAN", what);
                        assertThat(body(r).get("description").asText()).isEqualTo("Your plan does not include this feature.");
                        List<String> required = new ArrayList<>();
                        body(r).at("/data/requiredPlans").forEach(n -> required.add(n.asText()));
                        assertThat(required).as(what).isEqualTo(plansWith(row.feature()));
                    }
                } catch (AssertionError e) {
                    problems.add(e.getMessage());
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(cells).isEqualTo(3 * rows().size());
    }

    @Test
    void readOnly_canceled_gatedWritesAndLimitEntriesAre402Inactive_readsStayOpen() throws Exception {
        T t = tenant("p2-readonly");
        setPlan(t, TestPlans.SCALE_ID, "CANCELED", null);
        List<String> problems = new ArrayList<>();
        for (Row row : rows()) {
            MockHttpServletResponse r = send(t, t.admin(), row);
            try {
                if (row.read()) {
                    assertPassedTheGate(r, "read " + row.handler());
                } else {
                    assert402(r, "PLAN_UPGRADE_REQUIRED", row.feature(), "SUBSCRIPTION_INACTIVE", row.handler());
                    assertThat(body(r).get("description").asText())
                            .isEqualTo("Your subscription is not active. Existing data stays readable; upgrade to make changes.");
                }
            } catch (AssertionError e) {
                problems.add(e.getMessage());
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void notInPlanBeatsInactive_onAPlanWithoutTheFeature() throws Exception {
        T t = tenant("p2-readonly");
        setPlan(t, TestPlans.STARTER_ID, "CANCELED", null);
        MockHttpServletResponse r = send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/accounting/summary"), null);
        assert402(r, "PLAN_UPGRADE_REQUIRED", PlanFeature.ACCOUNTING, "NOT_IN_PLAN", "starter canceled accounting");
    }

    /** AC6: the trial end is evaluated per request against the Clock: expiry-1s writes, expiry and expiry+1s are read-only. */
    @Test
    void expiredTrial_onAFixedClock_atExpiryMinusOne_expiry_andExpiryPlusOne() throws Exception {
        T t = tenant("p2-trial");
        Instant end = Instant.parse("2030-06-01T12:00:00Z");
        setPlan(t, TestPlans.SCALE_ID, "TRIALING", end);
        Row write = rows().stream().filter(r -> r.handler().equals("ShopWideDiscountController#deactivate")).findFirst().orElseThrow();
        Row read = rows().stream().filter(r -> r.handler().equals("StockTransferController#history")).findFirst().orElseThrow();
        Row limit = rows().stream().filter(r -> r.handler().equals("StorageLocationController#create")).findFirst().orElseThrow();
        Row content = rows().stream().filter(r -> r.handler().equals("FeatureFlagController#updateFlag")).findFirst().orElseThrow();
        for (long offset : new long[]{-1, 0, 1}) {
            clock(end.plusSeconds(offset));
            boolean readOnly = offset >= 0;
            for (Row row : List.of(write, limit, content)) {
                MockHttpServletResponse r = send(t, t.admin(), row);
                if (readOnly) {
                    assert402(r, "PLAN_UPGRADE_REQUIRED", row.feature(), "SUBSCRIPTION_INACTIVE", offset + " " + row.handler());
                } else {
                    assertPassedTheGate(r, offset + " " + row.handler());
                }
            }
            assertPassedTheGate(send(t, t.admin(), read), offset + " read");
            JsonAssert sub = new JsonAssert(body(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/subscription"), null)));
            assertThat(sub.bool("/data/readOnly")).as("subscription readOnly at " + offset).isEqualTo(readOnly);
        }
        // ACTIVE and PAST_DUE are never read-only, whatever the trial end says
        for (String status : List.of("ACTIVE", "PAST_DUE")) {
            setPlan(t, TestPlans.SCALE_ID, status, end.minusSeconds(3600));
            assertPassedTheGate(send(t, t.admin(), write), status);
        }
    }

    private void clock(Instant at) {
        setClock(Clock.fixed(at, ZoneOffset.UTC));
    }

    /** plans.md 14.4 / 18 item 8: every 403 precedes every 402, on a plan without any of the features. */
    @Test
    void aFrontDeskUserWithoutRoleOrPermissionGets403_neverA402_onEveryGatedRoute() throws Exception {
        T t = tenant("p2-desk");
        setPlan(t, TestPlans.STARTER_ID, "CANCELED", null);
        Cookie desk = deniedFrontDesk(t);
        List<String> problems = new ArrayList<>();
        for (Row row : rows()) {
            MockHttpServletResponse r = send(t, desk, row);
            if (r.getStatus() != 403) {
                problems.add(row.handler() + " " + row.feature() + " -> " + r.getStatus() + " " + r.getContentAsString());
            }
        }
        assertThat(problems).isEmpty();
    }

    /** 14.4 step 4: bean validation runs before the gate (a malformed body is 400 even without the feature). */
    @Test
    void anInvalidBodyIs400BeforeThe402() throws Exception {
        T t = tenant("p2-desk");
        setPlan(t, TestPlans.STARTER_ID, "TRIALING", null);
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/walk-in/order"), "{}").getStatus()).isEqualTo(400);
        assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/feature-flags/LOGIN_OTP"), "{\"adminEnabled\":true}")
                .getStatus()).isEqualTo(400);
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/inventory/locations"), "{}").getStatus()).isEqualTo(400);
    }

    /**
     * plans.md 18 item 10 (behavioural half of the allow-list scan): EVERY handler without {@code @RequiresFeature}
     * (auth, onboarding, platform, storefront, cart, checkout, payment, webhooks, notifications, metrics, orders, returns
     * and refunds, finishing walk-in orders, legacy balance report / repair, the subscription endpoint, ...) never answers
     * 402, for an ADMIN of a tenant whose plan has nothing and whose subscription is CANCELED.
     */
    @Test
    void noUngatedRouteEverAnswers402_evenWithNothingInThePlanAndACanceledSubscription() throws Exception {
        T t = tenant("p2-core");
        createNonePlan();
        setPlan(t, NONE_ID, "CANCELED", null);
        int called = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(h.getMethod(), RequiresFeature.class).isEmpty()) {
                continue;
            }
            Set<String> patterns = e.getKey().getPatternValues();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                String path = pattern.replaceAll("\\{featureKey}", "LOGIN_OTP").replaceAll("\\{[^}]+}", "999999");
                for (org.springframework.web.bind.annotation.RequestMethod m : methods.isEmpty()
                        ? Set.of(org.springframework.web.bind.annotation.RequestMethod.GET) : methods) {
                    HttpMethod hm = HttpMethod.valueOf(m.name());
                    String json = hm == HttpMethod.GET || hm == HttpMethod.HEAD || hm == HttpMethod.DELETE ? null : "{}";
                    MockHttpServletResponse r = send(t, t.admin(), req(hm, path), json);
                    exec("UPDATE " + t.schema() + ".users SET token_version = 0, is_active = true WHERE id = ?", t.adminId());
                    called++;
                    if (r.getStatus() == 402) {
                        problems.add(hm + " " + path + " (" + h.getShortLogMessage() + ") -> 402 " + r.getContentAsString());
                    }
                }
            }
        }
        assertThat(called).as("handlers exercised").isGreaterThanOrEqualTo(130);
        // the admin was still signed in throughout (a 401 everywhere would prove nothing)
        assertThat(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/subscription"), null).getStatus()).isEqualTo(200);
        assertThat(problems).isEmpty();
    }

    /** Small JSON pointer helper. */
    record JsonAssert(com.fasterxml.jackson.databind.JsonNode n) {
        boolean bool(String ptr) {
            return n.at(ptr).asBoolean();
        }
    }
}

package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.TestPlans;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01c AC7 at runtime: a request under a never-gated billing path answers 200 while the tenant is READ_ONLY (the member
 * has to be able to pay), the same tenant is refused with 402 on a gated route, and a plan check that is (wrongly) run
 * inside a billing request fails loudly instead of answering 402. The probes stand in for the billing controllers that
 * BILL-01b / BILL-02 add; each of those gets its own family test there.
 */
@Import(BillingNeverGatedIntegrationTest.Probes.class)
class BillingNeverGatedIntegrationTest extends AbstractEntitlementIntegrationTest {

    @TestConfiguration
    static class Probes {
        @org.springframework.context.annotation.Bean
        BillingProbe billingProbe(EntitlementService entitlements) {
            return new BillingProbe(entitlements);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin")
    static class BillingProbe {
        private final EntitlementService entitlements;

        BillingProbe(EntitlementService entitlements) {
            this.entitlements = entitlements;
        }

        @PostMapping("/billing/probe")
        Map<String, Object> pay() {
            return Map.of("ok", true);
        }

        @GetMapping("/billing/probe-gated")
        Map<String, Object> mistakenlyGated() {
            entitlements.requireWrite(PlanFeature.WALK_IN_POS); // a bug a billing endpoint must never have
            return Map.of("ok", true);
        }

        @PostMapping("/probe-gated")
        Map<String, Object> gated() {
            entitlements.requireWrite(PlanFeature.WALK_IN_POS);
            return Map.of("ok", true);
        }
    }

    @Autowired org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void aBillingEndpointAnswers200WhileTheTenantIsReadOnly_whereAGatedRouteAnswers402() throws Exception {
        T t = tenant("bill01c-ro");
        setPlan(t, TestPlans.SCALE_ID, "CANCELED", null); // READ_ONLY under the pre-billing rule (plans.md 13)
        MockHttpServletResponse gated = send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/probe-gated"), "{}");
        assertThat(gated.getStatus()).as(gated.getContentAsString()).isEqualTo(402);
        assertThat(body(gated).at("/data/reason").asText()).isEqualTo("SUBSCRIPTION_INACTIVE");

        MockHttpServletResponse billing = send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/billing/probe"), "{}");
        assertThat(billing.getStatus()).as(billing.getContentAsString()).isEqualTo(200);

        // trial over (also READ_ONLY): the same
        setPlan(t, TestPlans.SCALE_ID, "TRIALING", Instant.now().minusSeconds(3600));
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/probe-gated"), "{}").getStatus()).isEqualTo(402);
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/billing/probe"), "{}").getStatus()).isEqualTo(200);
    }

    @Test
    void aPlanCheckInsideABillingRequestFailsLoudly_itIsNeverA402() throws Exception {
        T t = tenant("bill01c-ro2");
        setPlan(t, TestPlans.SCALE_ID, "CANCELED", null);
        MockHttpServletResponse r = send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/billing/probe-gated"), null);
        assertThat(r.getStatus()).as("a programming error (500), never the 402 a gated route answers: " + r.getContentAsString())
                .isEqualTo(500);
        assertThat(r.getContentAsString()).doesNotContain("PLAN_UPGRADE_REQUIRED");
    }
}

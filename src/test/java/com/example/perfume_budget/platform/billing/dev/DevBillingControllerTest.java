package com.example.perfume_budget.platform.billing.dev;

import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.platform.billing.BillingClock;
import com.example.perfume_budget.platform.billing.IdleBillingRound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BILL-01 AC7 / billing.md 19.2: the dev billing clock moves ONLY the billing clock, never the global {@link Clock}; the
 * controller does not exist under the {@code prod} profile (even with the dev flag set) nor without the dev flag.
 */
class DevBillingControllerTest {

    static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    @Configuration
    static class Beans {
        @Bean Clock globalClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean BillingClock billingClock(Clock c) { return new BillingClock(c); }
        @Bean IdleBillingRound round() { return new IdleBillingRound(); }
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Beans.class, DevBillingController.class);
    }

    @Test
    void theBeanExistsOnlyWithTheDevFlagAndNeverUnderProd() {
        runner().withPropertyValues("tenant.dev-endpoints.enabled=true").withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .run(ctx -> assertThat(ctx).hasSingleBean(DevBillingController.class));
        runner().withPropertyValues("tenant.dev-endpoints.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(DevBillingController.class));
        runner().run(ctx -> assertThat(ctx).doesNotHaveBean(DevBillingController.class));
        runner().withPropertyValues("tenant.dev-endpoints.enabled=false").withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevBillingController.class));
        // the second, independent switch: the flag leaked into production config, the bean still does not exist
        runner().withPropertyValues("tenant.dev-endpoints.enabled=true").withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevBillingController.class));
    }

    // ------------------------------------------------------------------------------------------ behaviour

    Clock global;
    BillingClock billing;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        global = Clock.fixed(NOW, ZoneOffset.UTC);
        billing = new BillingClock(global);
        mvc = MockMvcBuilders.standaloneSetup(new DevBillingController(billing, new IdleBillingRound()))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int status) throws Exception {
        var r = mvc.perform(b).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return JSON.readTree(r.getContentAsString());
    }

    @Test
    void advanceBySecondsToAnInstantAndReset_andOnlyTheBillingClockMoves() throws Exception {
        JsonNode start = json(get("/api/v1/dev/billing/clock"), 200).get("data");
        assertThat(start.get("now").asText()).isEqualTo("2026-12-01T09:00:00Z");
        assertThat(start.get("offsetSeconds").asLong()).isZero();
        assertThat(start.get("frozen").asBoolean()).isFalse();

        JsonNode adv = json(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content("{\"seconds\":86400}"), 200).get("data");
        assertThat(adv.get("now").asText()).isEqualTo("2026-12-02T09:00:00Z");
        assertThat(adv.get("offsetSeconds").asLong()).isEqualTo(86400);
        assertThat(global.instant()).as("the GLOBAL clock (JWT, OTP, rate limits) is untouched").isEqualTo(NOW);

        JsonNode to = json(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content("{\"to\":\"2026-12-11T09:30:00Z\"}"), 200).get("data");
        assertThat(to.get("now").asText()).isEqualTo("2026-12-11T09:30:00Z");
        assertThat(to.get("offsetSeconds").asLong()).isEqualTo(865_800L);
        assertThat(billing.now()).isEqualTo(Instant.parse("2026-12-11T09:30:00Z"));

        JsonNode reset = json(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content("{\"reset\":true}"), 200).get("data");
        assertThat(reset.get("offsetSeconds").asLong()).isZero();
        assertThat(billing.now()).isEqualTo(NOW);
        assertThat(global.instant()).isEqualTo(NOW);
    }

    @Test
    void invalidAdvanceBodiesAre400AndChangeNothing() throws Exception {
        for (String bad : new String[]{"{\"seconds\":0}", "{\"seconds\":-5}", "{\"seconds\":\"60\"}", "{\"seconds\":63072001}", "{}",
                "{\"seconds\":1,\"reset\":true}", "{\"reset\":false}", "{\"to\":\"tomorrow\"}", "[]"}) {
            json(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content(bad), 400);
        }
        assertThat(billing.offsetSeconds()).isZero();
    }

    @Test
    void aBillingRoundRunsSynchronouslyAtTheBillingClocksNow() throws Exception {
        billing.advanceSeconds(3600);
        JsonNode d = json(post("/api/v1/dev/billing/run"), 200).get("data");
        assertThat(d.get("now").asText()).isEqualTo("2026-12-01T10:00:00Z");
        for (String f : new String[]{"trialCharges", "renewals", "retries", "graceExpiries", "reminders", "reconciled"}) {
            assertThat(d.get(f).asInt()).as(f).isZero();
        }
    }

    @Test
    void theBillingClockIsNotAClockBeanSoNothingElseCanInjectItByAccident() {
        assertThat(Clock.class.isAssignableFrom(BillingClock.class)).isFalse();
    }
}

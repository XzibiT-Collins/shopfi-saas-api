package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-03b F3/F5/F6, TEST profile (what dev looks like for swagger): actuator metrics/info/env... are never anonymous
 * (401), prometheus needs the scrape token (none configured here, so it is closed), health stays public without
 * details, and swagger/api-docs stay available in non-prod profiles.
 */
@AutoConfigureMockMvc
class ActuatorAndDocsExposureTest extends AbstractPostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired Environment env;
    @Autowired com.example.perfume_budget.support.TestTenants tenants;
    @Autowired com.example.perfume_budget.filter.JWTUtil jwtUtil;

    static final List<String> DENIED = List.of("metrics", "metrics/jvm.memory.used", "info", "env", "beans", "configprops",
            "loggers", "heapdump", "threaddump", "mappings", "caches", "conditions", "scheduledtasks", "health/liveness",
            "health/readiness", "prometheus", "refresh", "shutdown");

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void everyActuatorPathExceptHealthIsDeniedToAnonymousCallers() throws Exception {
        for (String p : DENIED) {
            assertThat(status(get("/actuator/" + p))).as("GET /actuator/" + p).isEqualTo(401);
            assertThat(status(head("/actuator/" + p))).as("HEAD /actuator/" + p).isEqualTo(401);
            assertThat(status(post("/actuator/" + p))).as("POST /actuator/" + p).isEqualTo(401);
        }
        assertThat(status(get("/actuator"))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Bearer anything-at-all-123456"))).as("no token configured").isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Bearer "))).isEqualTo(401);
    }

    @Test
    void aSignedInTenantAdminCannotReachMetricsInfoOrEnvEither() throws Exception {
        var acme = tenants.acme();
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken(acme.adminEmail(), "ADMIN", 1L, "Admin"));
        for (String p : List.of("metrics", "info", "env", "beans", "prometheus")) {
            int st = status(get("/actuator/" + p).header("X-Tenant-ID", "acme").cookie(new jakarta.servlet.http.Cookie("accessToken", token)));
            assertThat(st).as("signed-in ADMIN on /actuator/" + p).isIn(401, 403);
        }
    }

    @Test
    void healthIsPublicButCarriesNoDetails() throws Exception {
        for (MockHttpServletRequestBuilder b : List.of(get("/actuator/health"), head("/actuator/health"))) {
            MvcResult r = mvc.perform(b).andReturn();
            assertThat(r.getResponse().getStatus()).isIn(200, 503);
            String body = r.getResponse().getContentAsString();
            assertThat(body).doesNotContain("components").doesNotContain("details").doesNotContain("db").doesNotContain("redis");
        }
    }

    @Test
    void swaggerAndApiDocsStayAvailableOutsideProd() throws Exception {
        MvcResult docs = mvc.perform(get("/v3/api-docs")).andReturn();
        assertThat(docs.getResponse().getStatus()).as("api-docs in the test profile").isEqualTo(200);
        assertThat(docs.getResponse().getContentAsString()).contains("openapi");
        assertThat(status(get("/swagger-ui.html"))).as("swagger-ui in the test profile").isIn(200, 302);
        assertThat(env.getProperty("springdoc.api-docs.enabled", "true")).isEqualTo("true");
    }
}

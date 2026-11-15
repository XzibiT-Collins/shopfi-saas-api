package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-03b F3/F5/F6 under the REAL prod profile with the real Prometheus registry (metrics export enabled): metrics, info
 * and env are exposed by the prod properties yet anonymous callers get 401; prometheus answers only with the scrape token;
 * health shows no details; swagger-ui and /v3/api-docs are off (401, never 200) in prod.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=false",
        "cors.allowed-origins=https://acme.shopfi.test",
        "shopfi.metrics.scrape-token=scrape-token-for-tests-0123456789"
})
class ActuatorAndDocsProdBootTest extends AbstractPostgresIntegrationTest {

    static final String TOKEN = "scrape-token-for-tests-0123456789";

    @Autowired MockMvc mvc;
    @Autowired Environment env;

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void prodExposesMetricsInfoAndPrometheus_yetAnonymousCannotReachThem() throws Exception {
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).contains("metrics", "info", "prometheus");
        for (String p : List.of("metrics", "metrics/jvm.memory.used", "info", "env", "beans", "configprops", "loggers",
                "heapdump", "threaddump", "mappings", "caches", "health/liveness", "prometheus")) {
            assertThat(status(get("/actuator/" + p))).as("GET /actuator/" + p).isEqualTo(401);
            assertThat(status(head("/actuator/" + p))).as("HEAD /actuator/" + p).isEqualTo(401);
        }
        // the scrape token opens ONLY prometheus
        for (String p : List.of("metrics", "info", "env", "beans", "loggers")) {
            assertThat(status(get("/actuator/" + p).header("Authorization", "Bearer " + TOKEN))).as("token on /actuator/" + p).isEqualTo(401);
        }
    }

    @Test
    void prometheusNeedsTheScrapeToken() throws Exception {
        assertThat(status(get("/actuator/prometheus"))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Bearer wrong-token-0123456789abcdef"))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN.substring(1)))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN + "x"))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", "Basic " + TOKEN))).isEqualTo(401);
        assertThat(status(get("/actuator/prometheus").header("Authorization", TOKEN))).isEqualTo(401);
        assertThat(status(post("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))).isEqualTo(401);
        MvcResult ok = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN)).andReturn();
        assertThat(ok.getResponse().getStatus()).as("real registry answers to the scrape token").isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("# HELP");
    }

    @Test
    void healthIsPublicWithoutDetails() throws Exception {
        MvcResult r = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(r.getResponse().getStatus()).isIn(200, 503);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("components").doesNotContain("details");
        assertThat(status(head("/actuator/health"))).isIn(200, 503);
    }

    @Test
    void swaggerUiAndApiDocsAreOffInProd() throws Exception {
        assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        for (String p : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml", "/swagger-ui.html",
                "/swagger-ui/index.html")) {
            assertThat(status(get(p))).as("GET " + p).isEqualTo(401);
            assertThat(status(head(p))).as("HEAD " + p).isEqualTo(401);
        }
    }
}

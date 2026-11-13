package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** TENANT-10 AC1: the unauthenticated POST /test/ws trigger no longer exists (test + pgtest profiles). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://acme.shopfi.test"
})
class TestEndpointRemovedTest extends AbstractPostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext ctx;

    @Test
    void noTestControllerBean() {
        assertThat(ctx.getBeanNamesForType(Object.class)).doesNotContain("testController");
    }

    @Test
    void testWsRejectedWithTenantOrigin() throws Exception {
        int status = mvc.perform(post("/test/ws").header("Origin", "https://acme.shopfi.test"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403, 404);
    }

    @Test
    void testWsRejectedWithNoTenantSignal() throws Exception {
        int status = mvc.perform(post("/test/ws")).andReturn().getResponse().getStatus();
        assertThat(status).isBetween(400, 499);
    }
}

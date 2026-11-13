package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** TENANT-10 AC1: POST /test/ws is rejected under the prod profile too. */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=false",
        "cors.allowed-origins=https://acme.shopfi.test"
})
class TestEndpointRemovedProdTest extends AbstractPostgresIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void testWsRejectedUnderProd() throws Exception {
        int withOrigin = mvc.perform(post("/test/ws").header("Origin", "https://acme.shopfi.test"))
                .andReturn().getResponse().getStatus();
        int noSignal = mvc.perform(post("/test/ws")).andReturn().getResponse().getStatus();
        assertThat(withOrigin).isIn(401, 403, 404);
        assertThat(noSignal).isBetween(400, 499);
    }
}

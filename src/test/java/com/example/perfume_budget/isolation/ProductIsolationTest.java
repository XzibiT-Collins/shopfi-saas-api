package com.example.perfume_budget.isolation;

import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TENANT-04 AC1: product create/list under tenant A is invisible under tenant B (API level). */
class ProductIsolationTest extends AbstractTenantIsolationTest {

    private String listAsAdmin(TestTenant t) throws Exception {
        return mockMvc.perform(get("/api/v1/product/admin/listing").header("X-Tenant-ID", t.slug()).cookie(adminCookie(t)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ac1_productCreatedUnderAcmeIsInvisibleUnderBravo() throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        createProductViaApi(acme, "AcmeOnlyScent Alpha");
        createProductViaApi(bravo, "BravoOnlyScent Omega");

        String acmeList = listAsAdmin(acme);
        String bravoList = listAsAdmin(bravo);
        assertThat(acmeList).contains("AcmeOnlyScent Alpha").doesNotContain("BravoOnlyScent");
        assertThat(bravoList).contains("BravoOnlyScent Omega").doesNotContain("AcmeOnlyScent");

        // physical rows live only in the owning schema
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE name LIKE 'Bravo%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_bravo.products WHERE name LIKE 'Acme%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_template.products", Integer.class)).isZero();
    }

    @Test
    void ac1_publicListingAndSearchAreScopedToResolvedTenant() throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        createProductViaApi(acme, "AcmePublicScent");
        createProductViaApi(bravo, "BravoPublicScent");
        publishAllProducts(acme);
        publishAllProducts(bravo);

        String acmeSearch = mockMvc.perform(get("/api/v1/product/search").header("X-Tenant-ID", "acme").param("searchTerm", "PublicScent"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bravoSearch = mockMvc.perform(get("/api/v1/product/search").header("X-Tenant-ID", "bravo").param("searchTerm", "PublicScent"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(acmeSearch).contains("AcmePublicScent").doesNotContain("BravoPublicScent");
        assertThat(bravoSearch).contains("BravoPublicScent").doesNotContain("AcmePublicScent");
    }

    @Test
    void ac1_tenantAdminTokenIsRejectedOnOtherTenant() throws Exception {
        TestTenant acme = tenants.acme();
        tenants.bravo(); // the target tenant must exist, otherwise the request is rejected as an unknown tenant (vacuous)
        // AUTH-01 (merged with main): an auth cookie minted for another tenant is IGNORED (the cookie is shared on the
        // single API host), so the request is anonymous. The path is permitAll at URL level with @PreAuthorize on the
        // method, hence the denial is exactly what an anonymous caller gets (JSON 403), never admin data. Main asserted
        // a plain-text 401 from JWTFilter; that 401 is what AUTH-01 AC2 removed (it locked users out of login/logout).
        int anonymous = mockMvc.perform(get("/api/v1/product/admin/listing").header("X-Tenant-ID", "bravo"))
                .andReturn().getResponse().getStatus();
        assertThat(anonymous).isIn(401, 403);
        mockMvc.perform(get("/api/v1/product/admin/listing").header("X-Tenant-ID", "bravo").cookie(adminCookie(acme)))
                .andExpect(status().is(anonymous));
    }
}

package com.example.perfume_budget.isolation;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers for TENANT-04: MockMvc through the full filter chain, admin JWTs and product creation. */
@AutoConfigureMockMvc
public abstract class AbstractTenantIsolationTest extends AbstractPostgresIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected TestTenants tenants;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JWTUtil jwtUtil;

    /** Tenant-bound admin access token (tenant claim comes from TenantContext, as in production login). */
    protected Cookie adminCookie(TestTenant tenant) {
        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT id, email, full_name FROM " + tenant.schemaName() + ".users WHERE email = ?", tenant.adminEmail());
        String token = tenants.runAs(tenant, () -> jwtUtil.generateAccessToken(
                (String) admin.get("email"), "ADMIN", ((Number) admin.get("id")).longValue(), (String) admin.get("full_name")));
        return new Cookie("accessToken", token);
    }

    /** Creates a (inactive, no image needed) base product through the real API as the tenant admin. */
    protected void createProductViaApi(TestTenant tenant, String name) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/product/add-product")
                        .header("X-Tenant-ID", tenant.slug())
                        .cookie(adminCookie(tenant))
                        .param("productName", name)
                        .param("currency", "GHS")
                        .param("sellingPrice", "100.00")
                        .param("costPrice", "60.00")
                        .param("isNewProduct", "true")
                        .param("isActive", "false")
                        .param("isEnlisted", "false"))
                .andExpect(status().isOk())
                .andReturn();
        if (result.getResponse().getContentAsString().isBlank()) {
            throw new IllegalStateException("empty create response");
        }
    }

    /** Makes every product in the tenant schema visible on the public storefront (the API requires an image otherwise). */
    protected void publishAllProducts(TestTenant tenant) {
        jdbc.update("UPDATE " + tenant.schemaName() + ".products SET is_active = true, is_enlisted = true");
    }
}

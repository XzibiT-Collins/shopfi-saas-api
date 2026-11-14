package com.example.perfume_budget.isolation;

import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TENANT-04 AC2. No Redis on this host, so "scan keys" is asserted at the TenantAwareCacheManager level
 * over the in-memory delegate: physical cache names are the "keys" namespace.
 */
class CachePrefixIsolationTest extends AbstractTenantIsolationTest {

    @Autowired CacheManager cacheManager;

    @Test
    void ac2_cachedReadsOnBothTenantsAreStoredUnderSlugPrefixedCaches() throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        createProductViaApi(acme, "CacheAcme Scent");
        createProductViaApi(bravo, "CacheBravo Scent");
        publishAllProducts(acme);
        publishAllProducts(bravo);

        // first read populates, second read must be served from the tenant's own cache
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "acme")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "bravo")).andExpect(status().isOk());
        }

        assertThat(cacheManager.getCacheNames()).contains("acme:customerProductListings", "bravo:customerProductListings");
        assertThat(cacheManager.getCacheNames()).allMatch(n -> n.contains(":"), "every physical cache name carries a tenant prefix");
        assertThat(cacheManager.getCacheNames()).doesNotContain("customerProductListings");

        String acmeCached = String.valueOf(tenants.runAs(acme, () -> cacheValues(cacheManager.getCache("customerProductListings"))));
        String bravoCached = String.valueOf(tenants.runAs(bravo, () -> cacheValues(cacheManager.getCache("customerProductListings"))));
        assertThat(acmeCached).contains("CacheAcme").doesNotContain("CacheBravo");
        assertThat(bravoCached).contains("CacheBravo").doesNotContain("CacheAcme");
    }

    @Test
    void ac2_platformScopeUsesPlatformPrefix() {
        TenantContext.set(TenantIdentity.platform());
        try {
            Cache c = cacheManager.getCache("someCache");
            assertThat(c.getName()).isEqualTo("__platform__:someCache");
            c.put("k", "platform-value");
        } finally {
            TenantContext.clear();
        }
        assertThat(cacheManager.getCacheNames()).contains("__platform__:someCache");
        TestTenant acme = tenants.acme();
        assertThat(tenants.runAs(acme, () -> cacheManager.getCache("someCache").get("k"))).isNull();
    }

    @Test
    void ac2_noTenantContextFallsBackToNeutralPrefixNeverATenantSlug() {
        Cache c = cacheManager.getCache("neutralProbe");
        assertThat(c.getName()).isEqualTo("tenant_template:neutralProbe");
    }

    private Object cacheValues(Cache cache) {
        return cache.getNativeCache() instanceof java.util.Map<?, ?> m ? m.values() : cache.getNativeCache();
    }
}

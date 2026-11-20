package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.service.BookkeepingService;
import com.example.perfume_budget.service.CatalogCaches;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * INV-BE-01: receiving or adjusting stock evicts the (tenant-prefixed) public catalog caches, so a product page cached
 * while sold out stops saying "Out of stock". Tenant bravo's entries survive acme's write, and a receipt that rolls
 * back evicts nothing and caches nothing.
 */
class StockWritesEvictCatalogCacheIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final List<String> CATALOG_CACHES = CatalogCaches.PUBLIC_CATALOG;
    static final String SENTINEL = "invbe-sentinel";

    @Autowired CacheManager cacheManager;
    @MockitoSpyBean BookkeepingService bookkeeping;

    @BeforeEach
    void clearCaches() {
        clear();
    }

    @AfterEach
    void clearCachesAfter() {
        clear();
    }

    private void clear() {
        for (var t : List.of(acme, bravo)) {
            CATALOG_CACHES.forEach(n -> cache(t, n).clear());
        }
    }

    private Cache cache(com.example.perfume_budget.support.TestTenant t, String name) {
        return tenants.runAs(t, () -> cacheManager.getCache(name));
    }

    private void sentinels(com.example.perfume_budget.support.TestTenant t) {
        CATALOG_CACHES.forEach(n -> cache(t, n).put(SENTINEL, "x"));
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder b) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        return mvc.perform(b.header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }

    private JsonNode detail(String slug) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/product/" + slug).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private String slug(long id) {
        return jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, id);
    }

    private MvcResult receive(long id, int qty, String unitCost) throws Exception {
        return asAdmin(post("/api/v1/admin/inventory/receipts").contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", id, "quantity", qty, "unitCost", unitCost,
                        "unitSellingPrice", "20.00", "reference", "invbe-po"))));
    }

    private MvcResult adjust(long id, String direction, int qty) throws Exception {
        return asAdmin(post("/api/v1/admin/inventory/adjustments").contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", id, "direction", direction, "quantity", qty,
                        "reason", "invbe", "unitCost", "5.00", "unitSellingPrice", "20.00"))));
    }

    @Test
    void aProductPageCachedWhileSoldOutRefreshesAfterAReceipt() throws Exception {
        long p = product(acme, "Invbe Sold Out", "20.00", 0);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isTrue();
        assertThat(cache(acme, CatalogCaches.PRODUCT_DETAILS).get(slug(p))).as("page cached").isNotNull();

        MvcResult r = receive(p, 5, "5.00");

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).as("fresh page after receipt").isFalse();
    }

    @Test
    void aProductPageCachedWhileSoldOutRefreshesAfterAnAdjustmentIncrease() throws Exception {
        long p = product(acme, "Invbe Adjust Up", "20.00", 0);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isTrue();

        MvcResult r = adjust(p, "INCREASE", 3);

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isFalse();
    }

    @Test
    void aProductPageCachedInStockRefreshesAfterAnAdjustmentDecrease() throws Exception {
        long p = product(acme, "Invbe Adjust Down", "20.00", 0);
        assertThat(receive(p, 2, "5.00").getResponse().getStatus()).isEqualTo(200);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isFalse();

        MvcResult r = adjust(p, "DECREASE", 2);

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isTrue();
    }

    @Test
    void receiptAndAdjustmentEvictAllThreeCatalogCachesOfTheTenant() throws Exception {
        long p = product(acme, "Invbe All Three", "20.00", 1);
        sentinels(acme);
        assertThat(receive(p, 1, "5.00").getResponse().getStatus()).isEqualTo(200);
        CATALOG_CACHES.forEach(n -> assertThat(cache(acme, n).get(SENTINEL)).as(n + " after receipt").isNull());

        sentinels(acme);
        assertThat(adjust(p, "INCREASE", 1).getResponse().getStatus()).isEqualTo(200);
        CATALOG_CACHES.forEach(n -> assertThat(cache(acme, n).get(SENTINEL)).as(n + " after adjustment").isNull());
    }

    @Test
    void tenantAsReceiptAndAdjustmentDoNotEvictTenantBsCaches() throws Exception {
        long p = product(acme, "Invbe Isolation", "20.00", 1);
        sentinels(bravo);

        assertThat(receive(p, 1, "5.00").getResponse().getStatus()).isEqualTo(200);
        assertThat(adjust(p, "INCREASE", 1).getResponse().getStatus()).isEqualTo(200);

        CATALOG_CACHES.forEach(n -> assertThat(cache(bravo, n).get(SENTINEL)).as(n + " of bravo kept").isNotNull());
    }

    @Test
    void aReceiptThatRollsBackLeavesTheCachesAlone() throws Exception {
        long p = product(acme, "Invbe Rollback", "20.00", 0);
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).isTrue();
        sentinels(acme);
        Mockito.doThrow(new IllegalStateException("ledger down")).when(bookkeeping)
                .recordInventoryPurchase(Mockito.any(), Mockito.anyInt(), Mockito.any());
        try {
            MvcResult r = receive(p, 5, "5.00");
            assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        } finally {
            Mockito.reset(bookkeeping);
        }

        assertThat(layerRemaining(acme, p)).as("receipt rolled back").isZero();
        CATALOG_CACHES.forEach(n -> assertThat(cache(acme, n).get(SENTINEL)).as(n + " not evicted").isNotNull());
        assertThat(detail(slug(p)).get("isOutOfStock").asBoolean()).as("page still truthful (sold out)").isTrue();
    }
}

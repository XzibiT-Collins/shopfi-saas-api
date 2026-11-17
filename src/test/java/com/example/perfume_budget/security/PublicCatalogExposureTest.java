package com.example.perfume_budget.security;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-03b F2: the anonymous storefront reads must not reveal exact stock or merchandising flags; the ADMIN and
 * FRONT_DESK listings keep them; the shared listing cache only ever holds the public shape.
 */
class PublicCatalogExposureTest extends AbstractCheckoutIntegrationTest {

    @Autowired CacheManager cacheManager;

    static final Set<String> PUBLIC_KEYS = Set.of("productId", "productName", "productShortDescription", "productImageUrl",
            "categoryName", "price", "originalPrice", "onSale", "discountPercentage", "discountEndsAt", "isOutOfStock", "slug");
    static final List<String> LEAKS = List.of("stockQuantity", "isActive", "isEnlisted", "lowStockThreshold", "soldCount", "sku");

    /**
     * CAT-02: fixtures are written with SQL (no eviction), and other classes cache catalog pages of rows that the base
     * class later truncates; start every test from empty catalog caches instead of depending on the class order.
     */
    @BeforeEach
    void clearCatalogCaches() {
        for (String name : List.of("publicCustomerProductListings", "publicFeaturedProducts", "productDetailsPage")) {
            tenants.runAs(acme, () -> cacheManager.getCache(name)).clear();
        }
    }

    private long featured(long productId) {
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", productId);
        return productId;
    }

    private String frontDeskToken() {
        String email = "fd-" + System.nanoTime() + "@acme.test";
        Long id = jdbc.queryForObject("INSERT INTO tenant_acme.users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('FD', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
        return tenants.runAs(acme, () -> jwtUtil.generateAccessToken(email, "FRONT_DESK", id, "FD"));
    }

    private String adminToken() throws Exception {
        return setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
    }

    private JsonNode items(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode data = body(r).get("data");
        return data.isArray() ? data : data.get("content");
    }

    private void assertNoLeak(JsonNode items) {
        assertThat(items.size()).isGreaterThan(0);
        for (JsonNode item : items) {
            Set<String> keys = new TreeSet<>();
            item.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("public item keys").isEqualTo(new TreeSet<>(PUBLIC_KEYS));
            for (String leak : LEAKS) {
                assertThat(item.has(leak)).as("public response must not contain " + leak).isFalse();
            }
        }
    }

    @Test
    void anonymousListingSearchAndFeaturedHaveNoExactStockOrFlags_butKeepTheAvailabilityFlag() throws Exception {
        long inStock = featured(product(acme, "Exposure InStock", "10.00", 7));
        long soldOut = product(acme, "Exposure SoldOut", "10.00", 0);
        long hidden = product(acme, "Exposure Hidden", "10.00", 3);
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", hidden);

        for (String path : List.of("/api/v1/product/listing", "/api/v1/product/search?searchTerm=Exposure", "/api/v1/product/featured")) {
            MvcResult r = mvc.perform(get(path).header("X-Tenant-ID", "acme")).andReturn();
            JsonNode items = items(r);
            assertNoLeak(items);
            String raw = r.getResponse().getContentAsString();
            assertThat(raw).doesNotContain("stockQuantity").doesNotContain("isEnlisted").doesNotContain("isActive");
            assertThat(raw).as("unlisted product stays hidden").doesNotContain("Exposure Hidden");
            for (JsonNode item : items) {
                if (item.get("productId").asLong() == inStock) {
                    assertThat(item.get("isOutOfStock").asBoolean()).as(path).isFalse();
                }
                if (item.get("productId").asLong() == soldOut) {
                    assertThat(item.get("isOutOfStock").asBoolean()).as(path).isTrue();
                }
            }
        }
        // a sold-out product is visible and flagged, in-stock one unflagged, on the listing
        JsonNode listing = items(mvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "acme")).andReturn());
        List<Boolean> flags = new ArrayList<>();
        listing.forEach(i -> flags.add(i.get("isOutOfStock").asBoolean()));
        assertThat(flags).contains(true, false);
    }

    @Test
    void adminAndFrontDeskListingsKeepTheFullShape() throws Exception {
        long p = product(acme, "Exposure Admin", "10.00", 7);
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", p);
        String admin = adminToken();
        String fd = frontDeskToken();

        // warm the public cache first: it must not leak into (or replace) the admin shape
        mvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "acme")).andReturn();

        for (String path : List.of("/api/v1/product/admin/listing", "/api/v1/product/admin/search?searchTerm=Exposure")) {
            JsonNode rows = items(mvc.perform(get(path).header("X-Tenant-ID", "acme").cookie(cookie("accessToken", admin))).andReturn());
            JsonNode row = null;
            for (JsonNode i : rows) {
                if (i.get("productId").asLong() == p) {
                    row = i;
                }
            }
            assertThat(row).as(path).isNotNull();
            assertThat(row.get("stockQuantity").asInt()).isEqualTo(7);
            assertThat(row.get("isActive").asBoolean()).isTrue();
            assertThat(row.get("isEnlisted").asBoolean()).isFalse();
        }
        JsonNode fdRows = items(mvc.perform(get("/api/v1/product/admin/search").param("searchTerm", "Exposure")
                .header("X-Tenant-ID", "acme").cookie(cookie("accessToken", fd))).andReturn());
        assertThat(fdRows.get(0).get("stockQuantity").asInt()).isEqualTo(7);
    }

    @Test
    void theSharedListingCacheOnlyHoldsThePublicShape() throws Exception {
        product(acme, "Exposure Cache", "10.00", 4);
        String admin = adminToken();
        mvc.perform(get("/api/v1/product/admin/listing").header("X-Tenant-ID", "acme").cookie(cookie("accessToken", admin))).andReturn();
        mvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "acme")).andReturn();
        mvc.perform(get("/api/v1/product/listing").header("X-Tenant-ID", "acme")).andReturn();

        Cache cache = tenants.runAs(acme, () -> cacheManager.getCache("publicCustomerProductListings"));
        Object nativeCache = cache.getNativeCache();
        assertThat(nativeCache).isInstanceOf(java.util.Map.class);
        java.util.Collection<?> values = ((java.util.Map<?, ?>) nativeCache).values();
        assertThat(values).isNotEmpty();
        for (Object v : values) {
            assertThat(v).isInstanceOf(PageResponse.class);
            for (Object item : ((PageResponse<?>) v).content()) {
                assertThat(item).as("cache entry element").isInstanceOf(PublicProductListing.class).isNotInstanceOf(ProductListing.class);
            }
        }
    }
}

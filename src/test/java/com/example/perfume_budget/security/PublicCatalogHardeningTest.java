package com.example.perfume_budget.security;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * SEC-03b rework: (1) pre-deploy Redis entries of the OLD shape never reach anonymous callers, (2) the public sort and
 * search cannot be used as an oracle for hidden fields, (3) HEAD does not count a view or a site visit.
 */
class PublicCatalogHardeningTest extends AbstractCheckoutIntegrationTest {

    @Autowired CacheManager cacheManager;

    @org.junit.jupiter.api.BeforeEach
    void clearCatalogCaches() {
        // fixtures are written with SQL (no eviction), so drop whatever earlier tests cached for the tenant
        for (String n : List.of("publicCustomerProductListings", "publicFeaturedProducts", "customerProductListings", "featuredProducts")) {
            cache(n).clear();
        }
    }

    static final List<String> HIDDEN = List.of("stockQuantity", "isActive", "isEnlisted");

    private ProductListing oldShape(String name) {
        return ProductListing.builder().productId(1L).productName(name).productShortDescription("s").productImageUrl("")
                .categoryName("c").price("GHS 1.00").originalPrice("GHS 1.00").isOutOfStock(false).isActive(true)
                .isEnlisted(true).slug("old-" + name).stockQuantity(777).build();
    }

    private PageResponse<ProductListing> oldPage(String name) {
        return PageResponse.<ProductListing>builder().content(List.of(oldShape(name))).totalPages(1).totalElements(1).size(20)
                .number(0).numberOfElements(1).isFirst(true).isLast(true).isEmpty(false).build();
    }

    private Cache cache(String name) {
        return tenants.runAs(acme, () -> cacheManager.getCache(name));
    }

    private void assertClean(String raw) {
        for (String f : HIDDEN) {
            assertThat(raw).doesNotContain(f);
        }
        assertThat(raw).doesNotContain("777");
    }

    private String anon(String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ 1. cache

    @Test
    void oldShapeEntriesUnderTheOldCacheNamesAreNeverRead() throws Exception {
        product(acme, "Fresh Scent", "10.00", 5);
        long feat = product(acme, "Fresh Featured", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", feat);
        // exactly what a pre-deploy node left in Redis: old DTO, old cache names, the key the old code used
        cache("customerProductListings").put("0:20:UNSORTED", oldPage("OldLeak"));
        cache("featuredProducts").put(SimpleKey.EMPTY, List.of(oldShape("OldLeakFeatured")));

        String listing = anon("/api/v1/product/listing");
        String featured = anon("/api/v1/product/featured");
        assertClean(listing);
        assertClean(featured);
        assertThat(listing).contains("Fresh Scent").doesNotContain("OldLeak");
        assertThat(featured).contains("Fresh Featured").doesNotContain("OldLeak");
        // the old names are not read (and not rewritten): the poisoned entries are still there, untouched
        assertThat(cache("customerProductListings").get("0:20:UNSORTED")).isNotNull();
        assertThat(cache("featuredProducts").get(SimpleKey.EMPTY)).isNotNull();
    }

    @Test
    void aWrongShapeEntryUnderTheNewNamesIsTreatedAsAMissAndReplaced() throws Exception {
        product(acme, "Defensive Scent", "10.00", 5);
        long feat = product(acme, "Defensive Featured", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", feat);
        cache("publicCustomerProductListings").put("0:20:UNSORTED", oldPage("PoisonedList"));
        cache("publicFeaturedProducts").put(SimpleKey.EMPTY, List.of(oldShape("PoisonedFeatured")));

        String listing = anon("/api/v1/product/listing");
        String featured = anon("/api/v1/product/featured");
        assertClean(listing);
        assertClean(featured);
        assertThat(listing).contains("Defensive Scent").doesNotContain("Poisoned");
        assertThat(featured).contains("Defensive Featured").doesNotContain("Poisoned");
        // and the cache now holds the rebuilt public page
        Object cached = cache("publicCustomerProductListings").get("0:20:UNSORTED").get();
        assertThat(String.valueOf(cached)).contains("PublicProductListing").doesNotContain("PoisonedList");
    }

    // ------------------------------------------------------------------ 2. sort / search oracle

    @Test
    void publicSortAcceptsOnlyPublicProperties() throws Exception {
        product(acme, "Sort One", "10.00", 5);
        product(acme, "Sort Two", "20.00", 6);
        for (String path : List.of("/api/v1/product/listing", "/api/v1/product/search")) {
            for (String bad : List.of("stockQuantity,asc", "costPrice.amount,desc", "costPrice", "soldCount,desc", "viewCount,desc",
                    "sku,asc", "isEnlisted,desc", "isActive,asc", "stock_quantity", "Name,asc", "price,asc", "category.name,asc")) {
                MvcResult r = mvc.perform(get(path).param("sort", bad).header("X-Tenant-ID", "acme")).andReturn();
                assertThat(r.getResponse().getStatus()).as(path + " sort=" + bad).isEqualTo(400);
                assertThat(body(r).at("/data/code").asText()).as(path + " sort=" + bad).isEqualTo("INVALID_SORT");
            }
            // a hidden property in a second sort parameter is rejected too
            assertThat(mvc.perform(get(path).param("sort", "name,asc").param("sort", "stockQuantity,desc").header("X-Tenant-ID", "acme"))
                    .andReturn().getResponse().getStatus()).as(path + " second sort").isEqualTo(400);
            for (String ok : List.of("name,asc", "name,desc", "createdAt,desc", "price.amount,asc", "price.amount,desc", "id,desc")) {
                assertThat(mvc.perform(get(path).param("sort", ok).header("X-Tenant-ID", "acme")).andReturn().getResponse().getStatus())
                        .as(path + " sort=" + ok).isEqualTo(200);
            }
        }
        // the allowed sorts actually order
        JsonNode asc = body(mvc.perform(get("/api/v1/product/listing").param("sort", "price.amount,asc").header("X-Tenant-ID", "acme")).andReturn()).at("/data/content");
        assertThat(asc.get(0).get("productName").asText()).isEqualTo("Sort One");
    }

    @Test
    void adminAndFrontDeskStillSortByHiddenFields_andSearchBySku() throws Exception {
        long p = product(acme, "Plain Name", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET sku = 'HIDDEN-SKU-9X' WHERE id = ?", p);
        String admin = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        for (String path : List.of("/api/v1/product/admin/listing", "/api/v1/product/admin/search")) {
            for (String sort : List.of("stockQuantity,asc", "sku,asc", "costPrice.amount,desc")) {
                assertThat(mvc.perform(get(path).param("sort", sort).header("X-Tenant-ID", "acme").cookie(cookie("accessToken", admin)))
                        .andReturn().getResponse().getStatus()).as(path + " " + sort).isEqualTo(200);
            }
        }
        JsonNode adminHit = body(mvc.perform(get("/api/v1/product/admin/search").param("searchTerm", "HIDDEN-SKU")
                .header("X-Tenant-ID", "acme").cookie(cookie("accessToken", admin))).andReturn()).at("/data/content");
        assertThat(adminHit.size()).as("admin finds by sku").isEqualTo(1);
    }

    @Test
    void anonymousSearchDoesNotMatchTheHiddenSku() throws Exception {
        long p = product(acme, "Plain Name", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET sku = 'HIDDEN-SKU-9X' WHERE id = ?", p);
        for (String term : List.of("HIDDEN-SKU", "hidden-sku-9x", "9X")) {
            JsonNode content = body(mvc.perform(get("/api/v1/product/search").param("searchTerm", term).header("X-Tenant-ID", "acme")).andReturn())
                    .at("/data/content");
            assertThat(content.size()).as("anonymous search by sku fragment " + term).isZero();
        }
        JsonNode byName = body(mvc.perform(get("/api/v1/product/search").param("searchTerm", "plain").header("X-Tenant-ID", "acme")).andReturn())
                .at("/data/content");
        assertThat(byName.size()).isEqualTo(1);
    }

    /** SEC-03d N5: the hidden SKU is not matched whichever other parameters ride along. */
    @Test
    void anonymousSearchDoesNotMatchTheHiddenSku_withCategoryRepeatedTermOrExtraParams() throws Exception {
        long p = product(acme, "Plain Name", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET sku = 'HIDDEN-SKU-9X' WHERE id = ?", p);
        long cat = jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, description, created_at) "
                + "VALUES ('Hidden Sku Cat', 'hidden-sku-cat-' || floor(random() * 1000000)::text, 'd', now()) RETURNING id", Long.class);
        jdbc.update("UPDATE tenant_acme.products SET category_id = ? WHERE id = ?", cat, p);
        try {
            String[][] variants = {
                    {"categoryId", String.valueOf(cat), "searchTerm", "HIDDEN-SKU"},
                    {"searchTerm", "HIDDEN-SKU", "searchTerm", "hidden-sku-9x"},
                    {"searchTerm", "plain", "searchTerm", "HIDDEN-SKU-9X"},
                    {"searchTerm", "HIDDEN-SKU-9X", "sku", "HIDDEN-SKU-9X"},
                    {"searchTerm", "HIDDEN-SKU-9X", "sort", "name,asc", "size", "5", "page", "0"},
                    {"categoryId", String.valueOf(cat), "searchTerm", "9X", "q", "HIDDEN-SKU-9X"},
            };
            for (String[] v : variants) {
                var req = get("/api/v1/product/search").header("X-Tenant-ID", "acme");
                for (int i = 0; i < v.length; i += 2) {
                    req = req.param(v[i], v[i + 1]);
                }
                JsonNode content = body(mvc.perform(req).andReturn()).at("/data/content");
                assertThat(content.size()).as("anonymous search " + String.join(",", v)).isZero();
            }
            // the category filter itself still works (the product is findable by its name inside it)
            JsonNode byName = body(mvc.perform(get("/api/v1/product/search").param("categoryId", String.valueOf(cat))
                    .param("searchTerm", "plain").header("X-Tenant-ID", "acme")).andReturn()).at("/data/content");
            assertThat(byName.size()).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE tenant_acme.products SET category_id = NULL WHERE id = ?", p);
            jdbc.update("DELETE FROM tenant_acme.categories WHERE id = ?", cat);
        }
    }

    /** SEC-03d N5b: an inactive but enlisted product is in no public search, listing or featured answer. */
    @Test
    void inactiveEnlistedProductIsAbsentFromPublicSearchListingAndFeatured() throws Exception {
        long visible = product(acme, "Zzvisible Scent", "10.00", 5);
        long hidden = product(acme, "Zzhidden Scent", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id IN (?, ?)", visible, hidden);
        jdbc.update("UPDATE tenant_acme.products SET is_active = false, is_enlisted = true WHERE id = ?", hidden);
        String search = anon("/api/v1/product/search?searchTerm=zz");
        String listing = anon("/api/v1/product/listing?size=100");
        String featured = anon("/api/v1/product/featured");
        for (String answer : List.of(search, listing, featured)) {
            assertThat(answer).contains("Zzvisible Scent").doesNotContain("Zzhidden");
        }
        String hiddenSlug = jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, hidden);
        assertThat(mvc.perform(get("/api/v1/product/" + hiddenSlug).header("X-Tenant-ID", "acme")).andReturn().getResponse().getStatus())
                .as("detail page of an inactive product").isEqualTo(404);
    }

    // ------------------------------------------------------------------ 3. HEAD

    private long views(long id) {
        return jdbc.queryForObject("SELECT view_count FROM tenant_acme.products WHERE id = ?", Long.class, id);
    }

    @Test
    void headDoesNotCountAViewButGetDoes() throws Exception {
        long p = product(acme, "View Scent", "10.00", 5);
        String slug = jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, p);
        jdbc.update("UPDATE tenant_acme.products SET view_count = 0 WHERE id = ?", p);
        long before = views(p);
        for (int i = 0; i < 3; i++) {
            assertThat(mvc.perform(head("/api/v1/product/" + slug).header("X-Tenant-ID", "acme")).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        Thread.sleep(1500);
        assertThat(views(p)).as("3 HEADs must not change view_count").isEqualTo(before);
        assertThat(mvc.perform(get("/api/v1/product/" + slug).header("X-Tenant-ID", "acme")).andReturn().getResponse().getStatus()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 8000;
        while (views(p) == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(views(p)).as("a GET still counts").isEqualTo(before + 1);
    }

    @Test
    void everyGetCountsAView_evenFromOneClient_documentedResidualUntilView01() throws Exception {
        // SEC-03c rework (PO decision on review F1): no per-client-IP dedupe on GET, because the storefront renders
        // product pages server-side and every visitor would share the frontend server's IP. A GET counts as on main;
        // real per-visitor counting is the browser-side beacon of VIEW-01.
        long p = product(acme, "Every Get Scent", "10.00", 5);
        String slug = jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, p);
        jdbc.update("UPDATE tenant_acme.products SET view_count = 0 WHERE id = ?", p);
        for (int i = 0; i < 3; i++) {
            assertThat(mvc.perform(get("/api/v1/product/" + slug).header("X-Tenant-ID", "acme")
                    .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        long deadline = System.currentTimeMillis() + 8000;
        while (views(p) < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(views(p)).as("3 GETs from the same client IP (e.g. the SSR frontend server) count 3 views").isEqualTo(3);
    }

    @Test
    void headDoesNotRecordASiteVisit() throws Exception {
        String path = "/api/v1/product/visit-probe-" + System.nanoTime(); // a page nobody visited today
        int before = count(acme, "site_visits");
        mvc.perform(head(path).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(count(acme, "site_visits")).as("HEAD is not a visit").isEqualTo(before);
        mvc.perform(get(path).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(count(acme, "site_visits")).as("GET is a visit").isEqualTo(before + 1);
    }
}

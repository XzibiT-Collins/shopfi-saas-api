package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CAT-02 (CAT-01 review lows):
 * (a) a category rename / delete evicts the three public catalog caches (the cached DTOs carry the category name);
 * (c) unlisted and inactive products never reach the public featured list or the public detail page (404), with real
 * rows rather than a mock verify.
 */
class CatalogCacheAndVisibilityIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final List<String> CATALOG_CACHES = List.of("publicCustomerProductListings", "publicFeaturedProducts", "productDetailsPage");
    static final String SENTINEL = "cat02-sentinel";

    @Autowired CacheManager cacheManager;

    String tag;

    @BeforeEach
    void clearCatalogCaches() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        CATALOG_CACHES.forEach(n -> cache(n).clear());
    }

    @AfterEach
    void dropOwnRows() {
        CATALOG_CACHES.forEach(n -> cache(n).clear());
        jdbc.update("UPDATE tenant_acme.products SET category_id = NULL WHERE category_id IN "
                + "(SELECT id FROM tenant_acme.categories WHERE description = 'cat02')");
        jdbc.update("DELETE FROM tenant_acme.categories WHERE description = 'cat02'");
    }

    private Cache cache(String name) {
        return tenants.runAs(acme, () -> cacheManager.getCache(name));
    }

    private void putSentinels() {
        for (String n : CATALOG_CACHES) {
            tenants.runAs(acme, () -> {
                cacheManager.getCache(n).put(SENTINEL, "stale " + n);
                return null;
            });
            assertThat(cache(n).get(SENTINEL)).as("sentinel written to " + n).isNotNull();
        }
    }

    private void assertSentinelsEvicted() {
        for (String n : CATALOG_CACHES) {
            assertThat(cache(n).get(SENTINEL)).as(n + " evicted").isNull();
        }
    }

    private void assertSentinelsKept() {
        for (String n : CATALOG_CACHES) {
            assertThat(cache(n).get(SENTINEL)).as(n + " kept").isNotNull();
        }
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder b) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        return mvc.perform(b.header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }

    private long category(String name) {
        return jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, description, created_at) "
                + "VALUES (?, ?, 'cat02', now()) RETURNING id", Long.class, name, name.toLowerCase());
    }

    private String slug(long productId) {
        return jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, productId);
    }

    private MvcResult anon(String path) throws Exception {
        return mvc.perform(get(path).header("X-Tenant-ID", "acme")).andReturn();
    }

    // ------------------------------------------------------------------ (a) category writes evict the catalog caches

    @Test
    void renamingACategoryEvictsTheThreeCatalogCaches() throws Exception {
        long id = category("OUD-" + tag);
        putSentinels();

        MvcResult r = asAdmin(put("/api/v1/category/update/" + id).contentType("application/json")
                .content(json.writeValueAsString(Map.of("categoryName", "Musk-" + tag, "description", "cat02"))));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(r).get("data").get("categoryName").asText()).isEqualTo("MUSK-" + tag.toUpperCase());
        assertSentinelsEvicted();
    }

    @Test
    void deletingACategoryEvictsTheThreeCatalogCaches() throws Exception {
        long id = category("GONE-" + tag);
        putSentinels();

        MvcResult r = asAdmin(delete("/api/v1/category/delete/" + id));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.categories WHERE id = ?", Integer.class, id)).isZero();
        assertSentinelsEvicted();
    }

    @Test
    void aFailedCategoryUpdateDoesNotEvict() throws Exception {
        putSentinels();

        MvcResult r = asAdmin(put("/api/v1/category/update/987654321").contentType("application/json")
                .content(json.writeValueAsString(Map.of("categoryName", "Nope-" + tag, "description", "cat02"))));

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertSentinelsKept();
    }

    @Test
    void creatingOrReadingACategoryDoesNotEvict() throws Exception {
        putSentinels();

        MvcResult created = asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/category/add-category")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("categoryName", "New-" + tag, "description", "cat02"))));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(anon("/api/v1/category/all").getResponse().getStatus()).isEqualTo(200);

        // a brand-new category is on no product yet, so nothing cached can be stale
        assertSentinelsKept();
    }

    @Test
    void theRenamedNameIsWhatTheNextPublicReadsCacheForAnUncategorisedShelf() throws Exception {
        // warm all three caches with real entries, then rename: every warmed entry is dropped, the next read rebuilds
        long p = product(acme, "Cat02 Shelf " + tag, "9.00", 3);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", p);
        assertThat(anon("/api/v1/product/listing").getResponse().getStatus()).isEqualTo(200);
        assertThat(anon("/api/v1/product/featured").getResponse().getStatus()).isEqualTo(200);
        assertThat(anon("/api/v1/product/" + slug(p)).getResponse().getStatus()).isEqualTo(200);
        assertThat(cache("productDetailsPage").get(slug(p))).as("detail cached").isNotNull();
        long id = category("AMBER-" + tag);

        MvcResult r = asAdmin(put("/api/v1/category/update/" + id).contentType("application/json")
                .content(json.writeValueAsString(Map.of("categoryName", "Resin-" + tag, "description", "cat02"))));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        assertThat(cache("productDetailsPage").get(slug(p))).as("detail evicted").isNull();
        for (String n : CATALOG_CACHES) {
            assertThat(((java.util.Map<?, ?>) cache(n).getNativeCache())).as(n + " emptied").isEmpty();
        }
    }

    private String nameOn(JsonNode items, long productId) {
        for (JsonNode i : items) {
            if (i.get("productId").asLong() == productId) {
                return i.get("categoryName").asText();
            }
        }
        throw new AssertionError("product " + productId + " not in " + items);
    }

    private String[] publicCategoryNames(long productId) throws Exception {
        MvcResult listing = anon("/api/v1/product/listing");
        MvcResult featured = anon("/api/v1/product/featured");
        MvcResult detail = anon("/api/v1/product/" + slug(productId));
        for (MvcResult r : List.of(listing, featured, detail)) {
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
        return new String[]{
                nameOn(body(listing).get("data").get("content"), productId),
                nameOn(body(featured).get("data"), productId),
                body(detail).get("data").get("category").asText()};
    }

    @Test
    void afterARenameTheNextPublicListingFeaturedAndDetailShowTheNewName() throws Exception {
        long id = category("CEDAR-" + tag);
        long p = product(acme, "Cat02 Cedar " + tag, "9.00", 3);
        jdbc.update("UPDATE tenant_acme.products SET category_id = ?, is_featured = true WHERE id = ?", id, p);
        // warm all three caches with the old name
        assertThat(publicCategoryNames(p)).containsOnly("CEDAR-" + tag);
        assertThat(cache("productDetailsPage").get(slug(p))).as("detail cached").isNotNull();

        MvcResult r = asAdmin(put("/api/v1/category/update/" + id).contentType("application/json")
                .content(json.writeValueAsString(Map.of("categoryName", "Vetiver-" + tag, "description", "cat02"))));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(publicCategoryNames(p)).containsOnly("VETIVER-" + tag.toUpperCase());
    }

    // ------------------------------------------------------------------ (c) hidden products stay hidden

    private List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(i -> out.add(i.get("productId").asLong()));
        return out;
    }

    @Test
    void featuredExcludesUnlistedAndInactiveProducts() throws Exception {
        long shown = product(acme, "Cat02 Shown " + tag, "10.00", 4);
        long unlisted = product(acme, "Cat02 Unlisted " + tag, "10.00", 4);
        long inactive = product(acme, "Cat02 Inactive " + tag, "10.00", 4);
        long notFeatured = product(acme, "Cat02 Plain " + tag, "10.00", 4);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id IN (?, ?, ?)", shown, unlisted, inactive);
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", unlisted);
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", inactive);

        MvcResult r = anon("/api/v1/product/featured");

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ids(body(r).get("data"))).containsExactly(shown).doesNotContain(unlisted, inactive, notFeatured);
    }

    @Test
    void theDetailPageOfAnUnlistedOrInactiveProductIs404() throws Exception {
        long shown = product(acme, "Cat02 Detail " + tag, "10.00", 4);
        long unlisted = product(acme, "Cat02 DUnlisted " + tag, "10.00", 4);
        long inactive = product(acme, "Cat02 DInactive " + tag, "10.00", 4);
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", unlisted);
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", inactive);

        MvcResult ok = anon("/api/v1/product/" + slug(shown));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("data").get("productId").asLong()).isEqualTo(shown);

        for (long hidden : List.of(unlisted, inactive)) {
            MvcResult r = anon("/api/v1/product/" + slug(hidden));
            assertThat(r.getResponse().getStatus()).as("detail of hidden " + hidden).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).doesNotContain(slug(hidden).substring(0, 10) + "\"");
            assertThat(cache("productDetailsPage").get(slug(hidden))).as("a 404 is not cached").isNull();
        }
        // the product listing hides them too
        JsonNode listing = body(anon("/api/v1/product/listing")).get("data").get("content");
        assertThat(ids(listing)).contains(shown).doesNotContain(unlisted, inactive);
    }
}

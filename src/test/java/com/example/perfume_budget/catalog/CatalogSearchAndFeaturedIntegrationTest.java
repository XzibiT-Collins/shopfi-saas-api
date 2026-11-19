package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAT-05: the storefront (and admin) product search treats the typed term as literal text: {@code %} and {@code _} are
 * not wildcards, the term is trimmed server side; {@code GET /product/featured?inStock=true} keeps only products with
 * stock (at most 8). Real app + Postgres; the rows are named "Qs ..." and removed after each test.
 */
class CatalogSearchAndFeaturedIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired CacheManager cacheManager;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tenant_acme.products WHERE name LIKE 'Qs %'");
        com.example.perfume_budget.service.CatalogCaches.PUBLIC_CATALOG
                .forEach(n -> tenants.runAs(acme, () -> { cacheManager.getCache(n).clear(); return null; }));
    }

    private void named(String... names) {
        for (String n : names) {
            product(acme, n, "10.00", 5);
        }
    }

    private List<String> search(String path, String term, boolean admin) throws Exception {
        var b = MockMvcRequestBuilders.get(path).param("searchTerm", term).param("size", "50").header("X-Tenant-ID", "acme");
        if (admin) {
            Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
            String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                    ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
            b.cookie(new Cookie("accessToken", token));
        }
        MvcResult r = mvc.perform(b).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        body(r).at("/data/content").forEach(n -> names.add(n.get("productName").asText()));
        return names;
    }

    private List<String> publicSearch(String term) throws Exception {
        return search("/api/v1/product/search", term, false);
    }

    @Test
    void percentAndUnderscoreAreLiteral_notWildcards() throws Exception {
        named("Qs 50% Off", "Qs 50x Off", "Qs a_b", "Qs aXb", "Qs plain");
        assertThat(publicSearch("qs 50%")).containsExactly("Qs 50% Off");
        assertThat(publicSearch("qs a_b")).containsExactly("Qs a_b");
        assertThat(publicSearch("%")).contains("Qs 50% Off").doesNotContain("Qs plain", "Qs a_b", "Qs 50x Off");
        assertThat(publicSearch("_")).contains("Qs a_b").doesNotContain("Qs plain", "Qs aXb", "Qs 50% Off");
        assertThat(publicSearch("qs _lain")).as("underscore does not stand for one character").isEmpty();
        assertThat(publicSearch("qs %lain")).isEmpty();
    }

    @Test
    void backslashIsLiteralToo_andDoesNotBreakTheEscape() throws Exception {
        named("Qs back\\slash", "Qs backslash");
        assertThat(publicSearch("qs back\\")).containsExactly("Qs back\\slash");
        assertThat(publicSearch("\\")).contains("Qs back\\slash").doesNotContain("Qs backslash");
        assertThat(publicSearch("qs back\\%")).isEmpty();
    }

    @Test
    void theTermIsTrimmedServerSide() throws Exception {
        named("Qs plain");
        assertThat(publicSearch("  qs plain  ")).containsExactly("Qs plain");
        assertThat(publicSearch("\tqs plain\n")).containsExactly("Qs plain");
        assertThat(publicSearch("   ")).as("blank term = no filter").contains("Qs plain");
    }

    @Test
    void adminSearchEscapesTooAndStillMatchesTheSku() throws Exception {
        long id = product(acme, "Qs 50% Off", "10.00", 5);
        product(acme, "Qs 50x Off", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET sku = 'QS-SKU_1' WHERE id = ?", id);
        assertThat(search("/api/v1/product/admin/search", "qs 50%", true)).containsExactly("Qs 50% Off");
        assertThat(search("/api/v1/product/admin/search", " qs-sku_1 ", true)).containsExactly("Qs 50% Off");
        assertThat(search("/api/v1/product/admin/search", "qs-sku%1", true)).isEmpty();
    }

    // ---------------------------------------------------------------- featured

    private List<String> featured(String query) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/featured" + query).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        body(r).get("data").forEach(n -> names.add(n.get("productName").asText()));
        return names;
    }

    @Test
    void featuredInStockOnlyShowsEightInStockItems_theDefaultKeepsEverything() throws Exception {
        jdbc.update("UPDATE tenant_acme.products SET is_featured = false");
        List<String> outOfStock = List.of("Qs Featured Out 1", "Qs Featured Out 2", "Qs Featured Out 3");
        for (String n : outOfStock) {
            long id = product(acme, n, "10.00", 0);
            jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", id);
        }
        for (int i = 1; i <= 8; i++) {
            long id = product(acme, "Qs Featured In " + i, "10.00", i);
            jdbc.update("UPDATE tenant_acme.products SET is_featured = true WHERE id = ?", id);
        }
        // the plain shelf is the first 8 featured, in-stock or not (unchanged behaviour)
        assertThat(featured("")).hasSize(8);
        // the in-stock shelf is 8 in-stock items: the three sold out ones leave room for all 8 stocked
        List<String> shelf = featured("?inStock=true");
        assertThat(shelf).hasSize(8).allMatch(n -> n.startsWith("Qs Featured In "));
        assertThat(featured("?inStock=false")).hasSize(8);
        // sold out later: it leaves the in-stock shelf even though the plain cache entry exists (caches are separate keys, evicted together)
        jdbc.update("UPDATE tenant_acme.products SET stock_quantity = 0 WHERE name = 'Qs Featured In 1'");
        com.example.perfume_budget.service.CatalogCaches.PUBLIC_CATALOG
                .forEach(n -> tenants.runAs(acme, () -> { cacheManager.getCache(n).clear(); return null; }));
        assertThat(featured("?inStock=true")).hasSize(7).doesNotContain("Qs Featured In 1");
    }

    @Test
    void featuredInStockOnlyListsPublicRowsOfThisTenantOnly() throws Exception {
        jdbc.update("UPDATE tenant_acme.products SET is_featured = false");
        jdbc.update("UPDATE tenant_bravo.products SET is_featured = false");
        long hidden = product(acme, "Qs Featured Hidden", "10.00", 5);
        jdbc.update("UPDATE tenant_acme.products SET is_featured = true, is_enlisted = false WHERE id = ?", hidden);
        long foreign = product(bravo, "Qs Featured Foreign", "10.00", 5);
        jdbc.update("UPDATE tenant_bravo.products SET is_featured = true WHERE id = ?", foreign);
        try {
            assertThat(featured("?inStock=true")).doesNotContain("Qs Featured Hidden", "Qs Featured Foreign");
        } finally {
            jdbc.update("DELETE FROM tenant_bravo.products WHERE id = ?", foreign);
        }
    }

    @Test
    void anInvalidInStockValueIs400() throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/featured?inStock=maybe").header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }
}

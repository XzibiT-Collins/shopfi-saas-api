package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.specification.ProductSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CAT-01: with {@code spring.jpa.open-in-view=false} and a LAZY {@code Product.category}, every catalog read that maps a
 * categorised product must load the category inside the query (entity graph), or the mapper's
 * {@code getCategory().getName()} throws LazyInitializationException after the session closed (500).
 * Covers the public listing / search / featured / detail (cache miss AND cache hit) and the admin listing / search /
 * detail, plus the repository contract that the category comes back initialised (no per-row lazy load, no N+1).
 */
class CatalogCategoryLazyLoadIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final String LISTINGS = "publicCustomerProductListings";
    static final String FEATURED = "publicFeaturedProducts";
    static final String DETAILS = "productDetailsPage";

    @Autowired CacheManager cacheManager;
    @Autowired ProductRepository productRepository;

    String tag;
    String categoryName;
    long categoryId;
    long productId;
    String slug;

    @BeforeEach
    void seedCategorisedProduct() {
        for (String n : List.of(LISTINGS, FEATURED, DETAILS)) {
            cache(n).clear(); // fixtures are written with SQL (no eviction): drop what earlier tests cached
        }
        tag = UUID.randomUUID().toString().substring(0, 8);
        categoryName = "Oud-" + tag;
        categoryId = jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, description, created_at) "
                + "VALUES (?, ?, 'woody', now()) RETURNING id", Long.class, categoryName, "oud-" + tag);
        productId = product(acme, "Cat01 Scent " + tag, "25.00", 4);
        jdbc.update("UPDATE tenant_acme.products SET category_id = ?, is_featured = true WHERE id = ?", categoryId, productId);
        slug = jdbc.queryForObject("SELECT slug FROM tenant_acme.products WHERE id = ?", String.class, productId);
    }

    @AfterEach
    void dropCategoriesAndCachedPages() {
        // the base class truncates the products: never leave pages of them cached for later test classes
        for (String n : List.of(LISTINGS, FEATURED, DETAILS)) {
            cache(n).clear();
        }
        jdbc.update("UPDATE tenant_acme.products SET category_id = NULL WHERE category_id IN "
                + "(SELECT id FROM tenant_acme.categories WHERE name LIKE 'Oud-%' OR name LIKE 'Musk-%')");
        jdbc.update("DELETE FROM tenant_acme.categories WHERE name LIKE 'Oud-%' OR name LIKE 'Musk-%'");
    }

    // ------------------------------------------------------------------ helpers

    private Cache cache(String name) {
        return tenants.runAs(acme, () -> cacheManager.getCache(name));
    }

    private JsonNode okData(MvcResult r, String what) throws Exception {
        assertThat(r.getResponse().getStatus()).as(what + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private JsonNode anon(String path, Object... params) throws Exception {
        var b = get(path).header("X-Tenant-ID", "acme");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param((String) params[i], String.valueOf(params[i + 1]));
        }
        return okData(mvc.perform(b).andReturn(), path);
    }

    private JsonNode admin(String path, Object... params) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        var b = get(path).header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token));
        for (int i = 0; i < params.length; i += 2) {
            b = b.param((String) params[i], String.valueOf(params[i + 1]));
        }
        return okData(mvc.perform(b).andReturn(), path);
    }

    private JsonNode item(JsonNode content, String field, Object value) {
        for (JsonNode n : content) {
            if (n.get(field).asText().equals(String.valueOf(value))) {
                return n;
            }
        }
        throw new AssertionError("no item with " + field + "=" + value + " in " + content);
    }

    private void renameCategory(String newName) {
        jdbc.update("UPDATE tenant_acme.categories SET name = ? WHERE id = ?", newName, categoryId);
    }

    /** The cached value is a plain serialisable DTO graph: no entity, no Hibernate proxy can be in it. */
    private static void assertSerialisableDto(Object value) throws Exception {
        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            out.writeObject(value);
        }
    }

    // ------------------------------------------------------------------ public routes

    @Test
    void publicListingWithACategorisedProductAnswers200OnCacheMissAndHit() throws Exception {
        JsonNode miss = anon("/api/v1/product/listing");
        assertThat(item(miss.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);

        // what was cached is the public DTO page, never an entity page
        Object cached = ((java.util.concurrent.ConcurrentMap<?, ?>) cache(LISTINGS).getNativeCache()).values().stream()
                .findFirst().map(v -> v instanceof Cache.ValueWrapper w ? w.get() : v).orElseThrow();
        assertThat(cached).isInstanceOf(PageResponse.class);
        assertThat(((PageResponse<?>) cached).content()).isNotEmpty().allMatch(PublicProductListing.class::isInstance);
        assertSerialisableDto(cached);

        // a hit is served from the cache (the rename is not seen) and still answers 200
        renameCategory("Musk-" + tag);
        JsonNode hit = anon("/api/v1/product/listing");
        assertThat(item(hit.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
    }

    @Test
    void publicSearchWithACategorisedProductAnswers200() throws Exception {
        JsonNode byTerm = anon("/api/v1/product/search", "searchTerm", "Cat01 Scent " + tag);
        assertThat(item(byTerm.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
        JsonNode byCategory = anon("/api/v1/product/search", "categoryId", categoryId);
        assertThat(byCategory.get("content")).hasSize(1);
        assertThat(item(byCategory.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
    }

    @Test
    void publicFeaturedWithACategorisedProductAnswers200OnCacheMissAndHit() throws Exception {
        JsonNode miss = anon("/api/v1/product/featured");
        assertThat(item(miss, "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);

        Cache.ValueWrapper w = cache(FEATURED).get(SimpleKey.EMPTY);
        assertThat(w).as("featured list cached").isNotNull();
        assertThat((List<?>) w.get()).isNotEmpty().allMatch(PublicProductListing.class::isInstance);
        assertSerialisableDto(w.get());

        renameCategory("Musk-" + tag);
        JsonNode hit = anon("/api/v1/product/featured");
        assertThat(item(hit, "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
    }

    @Test
    void publicDetailPageWithACategorisedProductAnswers200OnCacheMissAndHit() throws Exception {
        JsonNode miss = anon("/api/v1/product/" + slug);
        assertThat(miss.get("category").asText()).isEqualTo(categoryName);

        Cache.ValueWrapper w = cache(DETAILS).get(slug);
        assertThat(w).as("detail page cached").isNotNull();
        assertThat(w.get()).isInstanceOf(ProductDetailsPageResponse.class);
        assertSerialisableDto(w.get());

        renameCategory("Musk-" + tag);
        JsonNode hit = anon("/api/v1/product/" + slug);
        assertThat(hit.get("category").asText()).isEqualTo(categoryName);
        assertThat(hit.get("productId").asLong()).isEqualTo(productId);
    }

    @Test
    void anUncategorisedProductStillMapsToAnEmptyCategoryName() throws Exception {
        long plain = product(acme, "Cat01 Plain " + tag, "9.00", 1);
        JsonNode listing = anon("/api/v1/product/listing");
        assertThat(item(listing.get("content"), "productId", plain).get("categoryName").asText()).isEmpty();
        JsonNode admin = admin("/api/v1/product/main/" + plain);
        assertThat(admin.get("category").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ admin routes

    @Test
    void adminListingWithACategorisedProductAnswers200() throws Exception {
        JsonNode page = admin("/api/v1/product/admin/listing");
        assertThat(item(page.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
    }

    @Test
    void adminSearchWithACategorisedProductAnswers200() throws Exception {
        JsonNode page = admin("/api/v1/product/admin/search", "searchTerm", "Cat01 Scent " + tag);
        assertThat(item(page.get("content"), "productId", productId).get("categoryName").asText()).isEqualTo(categoryName);
        JsonNode byCategory = admin("/api/v1/product/admin/search", "categoryId", categoryId);
        assertThat(byCategory.get("content")).hasSize(1);
    }

    @Test
    void adminDetailWithACategorisedProductAnswers200() throws Exception {
        JsonNode d = admin("/api/v1/product/main/" + productId);
        assertThat(d.get("category").get("categoryName").asText()).isEqualTo(categoryName);
        assertThat(d.get("category").get("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(d.get("category").get("description").asText()).isEqualTo("woody");
    }

    // ------------------------------------------------------------------ repository contract (no lazy load, no N+1)

    @Test
    void everyCatalogQueryReturnsTheCategoryAlreadyInitialised() {
        // outside any transaction, like the services: the category must have been fetched by the query itself
        long second = product(acme, "Cat01 Second " + tag, "12.00", 2);
        long otherCategory = jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, created_at) "
                + "VALUES (?, ?, now()) RETURNING id", Long.class, "Musk-" + tag, "musk-" + tag);
        jdbc.update("UPDATE tenant_acme.products SET category_id = ?, is_featured = true WHERE id = ?", otherCategory, second);

        Map<String, List<Product>> results = tenants.runAs(acme, () -> Map.of(
                "public listing", productRepository.findAllByIsActiveTrueAndIsEnlistedTrue(PageRequest.of(0, 50)).getContent(),
                "public search", productRepository.findAll(ProductSpecification.filterProducts(null, "Cat01"), PageRequest.of(0, 50)).getContent(),
                "admin listing", productRepository.findAll(PageRequest.of(0, 50)).getContent(),
                "admin search", productRepository.findAll(ProductSpecification.filterProductsForAdmin(null, "Cat01"), PageRequest.of(0, 50)).getContent(),
                "featured", productRepository.findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue(),
                "detail page", productRepository.findBySlugAndIsActiveTrueAndIsEnlistedTrue(slug).stream().toList(),
                "admin detail", productRepository.findWithCategoryById(productId).stream().toList()));

        results.forEach((query, products) -> {
            assertThat(products).as(query).isNotEmpty();
            for (Product p : products) {
                if (p.getCategory() != null) {
                    assertThat(Hibernate.isInitialized(p.getCategory())).as(query + " category of product " + p.getId()).isTrue();
                }
            }
            assertThat(products).as(query + " returns a categorised product").anyMatch(p -> p.getCategory() != null);
        });
    }
}

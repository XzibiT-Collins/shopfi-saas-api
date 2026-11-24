package com.example.perfume_budget.theme;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THEME-02 on real PostgreSQL (schema-per-tenant) with the production tenant-prefixed cache wiring over an in-memory
 * delegate and a fake Cloudinary. Uses its own tenants (themea/themeb) so it never disturbs acme/bravo state that
 * other test classes assert on. Small Hikari pool.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=3")
class ThemeServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeBrandingStorage fakeBrandingStorage() {
            return new FakeBrandingStorage("test"); // matches storage.cloudinary.cloud-name=test
        }
    }

    @Autowired ThemeService themes;
    @Autowired TestTenants tenants;
    @Autowired CacheManager cacheManager;
    @Autowired FakeBrandingStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired ThemeStore themeStore;
    @MockitoSpyBean ThemeValidator validatorSpy;

    TestTenant a;
    TestTenant b;

    @BeforeEach
    void tenants() {
        a = tenants.ensureTenant("themea");
        b = tenants.ensureTenant("themeb");
        storage.reset();
    }

    private <T> T as(TestTenant t, Supplier<T> work) {
        return tenants.runAs(t, work);
    }

    private <T> T tx(TestTenant t, Supplier<T> work) {
        return as(t, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }

    private ThemeSnapshot get(TestTenant t) {
        return as(t, () -> themes.getActiveTheme());
    }

    private ThemeSnapshot put(TestTenant t, ObjectNode doc, Long expected) {
        return as(t, () -> themes.updateTheme(doc, expected, ThemeUploads.NONE, "admin@" + t.slug()));
    }

    /** SF-BE-01: tokens come from the catalog, so each former colour literal selects a distinct theme. */
    static String themeFor(String hex) {
        return switch (hex.toLowerCase()) {
            case "#111827" -> "classic";
            case "#7c2d12" -> "blush";
            case "#0b3d2e" -> "ocean";
            case "#000000" -> "slate";
            case "#0f172a" -> "plum";
            default -> "morrow";
        };
    }

    private ObjectNode withPrimary(String hex, String onPrimary) {
        ObjectNode d = defaults();
        d.put("themeId", themeFor(hex));
        return d;
    }

    private String primary(ThemeSnapshot s) {
        return s.theme().at("/tokens/colors/primary").asText();
    }

    private String dbPrimary(TestTenant t) {
        return jdbc.queryForObject("SELECT theme->'tokens'->'colors'->>'primary' FROM " + t.schemaName() + ".store_settings", String.class);
    }

    private long dbVersion(TestTenant t) {
        return jdbc.queryForObject("SELECT version FROM " + t.schemaName() + ".store_settings", Long.class);
    }

    // ---------------------------------------------------------------- AC1 cache

    @Test
    void getActiveTheme_isCachedUnderTenantPrefixedCache() {
        ThemeSnapshot first = get(a);
        ThemeSnapshot second = get(a);
        assertThat(second).isSameAs(first); // second call served from the cache, not rebuilt from the DB
        assertThat(cacheManager.getCacheNames()).contains("themea:theme");
        assertThat(first.version()).isGreaterThanOrEqualTo(1);
        assertThat(first.theme().get("revision").asLong()).isEqualTo(first.version() - 1);
        assertThat(first.etag()).isEqualTo("W/\"" + first.version() + "\"");
    }

    @Test
    void updateEvictsAndNextReadSeesNewValue() {
        ThemeSnapshot before = get(a);
        ThemeSnapshot saved = put(a, withPrimary("#0b3d2e", "#ffffff"), before.version());
        ThemeSnapshot after = get(a);
        assertThat(primary(after)).isEqualTo("#0f3d5c");
        assertThat(after).isNotSameAs(before);
        assertThat(after.version()).isEqualTo(before.version() + 1).isEqualTo(saved.version());
        assertThat(after.theme().get("revision").asLong()).isEqualTo(before.theme().get("revision").asLong() + 1);
        assertThat(get(a)).isSameAs(after); // and the fresh value is cached again
        assertThat(dbPrimary(a)).isEqualTo("#0f3d5c");
    }

    @Test
    void tenantsAreIsolatedInCacheAndStorage() {
        put(a, withPrimary("#111827", "#ffffff"), null);
        put(b, withPrimary("#111827", "#ffffff"), null);
        ThemeSnapshot bCached = get(b);
        ThemeSnapshot aBefore = get(a);

        put(a, withPrimary("#7c2d12", "#ffffff"), aBefore.version());

        assertThat(get(b)).isSameAs(bCached); // B's cached entry untouched by A's update (not even evicted)
        assertThat(primary(get(b))).isEqualTo("#111827");
        assertThat(primary(get(a))).isEqualTo("#7a2e4a");
        assertThat(dbPrimary(b)).isEqualTo("#111827");
        assertThat(cacheManager.getCacheNames()).contains("themea:theme", "themeb:theme");

        // and the other direction
        ThemeSnapshot aCached = get(a);
        put(b, withPrimary("#000000", "#ffffff"), bCached.version());
        assertThat(get(a)).isSameAs(aCached);
        assertThat(primary(get(a))).isEqualTo("#7a2e4a");
        assertThat(primary(get(b))).isEqualTo("#1e293b");
    }

    @Test
    void failedUpdateDoesNotEvictOrChangeAnything() {
        ThemeSnapshot cached = get(a);
        ObjectNode bad = defaults();
        ((ObjectNode) section(bad, "hero").get("props")).put("heading", "h".repeat(81));
        assertThatThrownBy(() -> put(a, bad, null)).isInstanceOf(ThemeValidationException.class);
        assertThat(get(a)).isSameAs(cached);
        assertThat(dbVersion(a)).isEqualTo(cached.version());
    }

    // ---------------------------------------------------------------- versioning / If-Match

    @Test
    void saveStampsRevisionUpdatedAtAndKeepsSchemaVersionColumnInSync() {
        ThemeSnapshot before = get(a);
        ObjectNode doc = withPrimary("#0F172A", "#FFFFFF"); // upper case in, lower case out
        doc.put("revision", 12345);
        doc.put("updatedAt", "2000-01-01T00:00:00Z");
        ThemeSnapshot saved = put(a, doc, null);
        JsonNode stored = saved.theme();
        assertThat(stored.get("revision").asLong()).isEqualTo(saved.version() - 1).isEqualTo(before.version());
        assertThat(stored.get("updatedAt").asText()).isNotEqualTo("2000-01-01T00:00:00Z").endsWith("Z");
        assertThat(stored.at("/tokens/colors/primary").asText()).isEqualTo("#4a2c6d");
        assertThat(jdbc.queryForObject("SELECT schema_version FROM " + a.schemaName() + ".store_settings", Integer.class))
                .isEqualTo(stored.get("schemaVersion").asInt());
        assertThat(jdbc.queryForObject("SELECT updated_by FROM " + a.schemaName() + ".store_settings", String.class)).isEqualTo("admin@themea");
        // the stored JSON and the entity timestamp agree
        String dbUpdated = jdbc.queryForObject("SELECT to_char(updated_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') FROM " + a.schemaName() + ".store_settings", String.class);
        assertThat(java.time.Instant.parse(get(a).theme().get("updatedAt").asText())).isEqualTo(java.time.Instant.parse(dbUpdated));
        assertThat(get(a).theme().get("revision").asLong()).isEqualTo(dbVersion(a) - 1);
    }

    @Test
    void staleExpectedVersionIsAConflictAndWritesNothing() {
        ThemeSnapshot v = get(a);
        put(a, withPrimary("#111827", "#ffffff"), v.version());          // admin 1 wins
        assertThatThrownBy(() -> put(a, withPrimary("#7c2d12", "#ffffff"), v.version())) // admin 2 has a stale ETag
                .isInstanceOf(ThemeConflictException.class);
        assertThat(dbPrimary(a)).isEqualTo("#111827");
        assertThat(primary(get(a))).isEqualTo("#111827"); // cache was evicted by the conflict, not left stale
        assertThat(dbVersion(a)).isEqualTo(v.version() + 1);
    }

    @Test
    void ifMatchOfTheCurrentVersionSucceedsAndOmittedMeansLastWriteWins() {
        ThemeSnapshot v = get(a);
        ThemeSnapshot s1 = put(a, withPrimary("#111827", "#ffffff"), ThemeSnapshot.parseEtag(v.etag()));
        ThemeSnapshot s2 = put(a, withPrimary("#7c2d12", "#ffffff"), null);
        assertThat(s2.version()).isEqualTo(s1.version() + 1);
        assertThat(dbPrimary(a)).isEqualTo("#7a2e4a");
    }

    @Test
    void concurrentWritersExactlyOneWins() throws Exception {
        ThemeSnapshot v = get(a);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Object>> fs = new java.util.ArrayList<>();
            for (String color : new String[]{"#111827", "#7c2d12"}) {
                fs.add(pool.submit(() -> {
                    start.await();
                    try {
                        return put(a, withPrimary(color, "#ffffff"), v.version());
                    } catch (ThemeConflictException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            long wins = 0, conflicts = 0;
            for (var f : fs) {
                Object r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof ThemeSnapshot) wins++; else conflicts++;
            }
            assertThat(wins).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(dbVersion(a)).isEqualTo(v.version() + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- self-heal

    @Test
    void missingSingletonRowIsRecreatedWithDefaultOnRead() {
        TestTenant healer = tenants.ensureTenant("themeheal");
        jdbc.update("DELETE FROM " + healer.schemaName() + ".store_settings");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + healer.schemaName() + ".store_settings", Integer.class)).isZero();
        cacheManager.getCache("x"); // no-op; cache for healer is empty (never read)
        ThemeSnapshot healed = get(healer);
        assertThat(healed.version()).isEqualTo(1);
        assertThat(healed.theme().get("revision").asInt()).isZero();
        assertThat(healed.theme().at("/pages/home/0/type").asText()).isEqualTo("hero");
        assertThat(healed.theme().at("/tokens/colors/primary").asText()).isEqualTo("#35432f"); // SF-BE-01: untouched default resolves to morrow
        assertThat(healed.theme().get("themeId").asText()).isEqualTo("morrow");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + healer.schemaName() + ".store_settings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT schema_version FROM " + healer.schemaName() + ".store_settings", Integer.class)).isEqualTo(1);
        // and the healed default is a valid document that can be updated
        ThemeSnapshot saved = put(healer, withPrimary("#0b3d2e", "#ffffff"), healed.version());
        assertThat(saved.version()).isEqualTo(2);
    }

    @Test
    void missingRowIsRecreatedOnUpdateToo() {
        TestTenant healer = tenants.ensureTenant("themeheal2");
        jdbc.update("DELETE FROM " + healer.schemaName() + ".store_settings");
        ThemeSnapshot saved = put(healer, withPrimary("#0b3d2e", "#ffffff"), null);
        assertThat(saved.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + healer.schemaName() + ".store_settings", Integer.class)).isEqualTo(1);
        assertThat(dbPrimary(healer)).isEqualTo("#0f3d5c");
    }


    /**
     * 6 threads x 5 rounds all read a tenant whose singleton row was deleted. The V9 unique index is dropped in this
     * tenant's schema first, so ONLY the advisory lock in {@code ThemeStore.heal} stands between the threads and a
     * second row: removing the lock makes this test fail (mutation-checked).
     */
    @Test
    void concurrentSelfHealCreatesExactlyOneRowUnderTheAdvisoryLock() throws Exception {
        TestTenant healer = tenants.ensureTenant("themeheal3");
        String schema = healer.schemaName();
        jdbc.execute("DROP INDEX IF EXISTS " + schema + ".uq_store_settings_singleton");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 1; round <= 5; round++) {
                jdbc.update("DELETE FROM " + schema + ".store_settings");
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Long>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        return tenants.runAs(healer, () -> themeStore.read().version());
                    }));
                }
                for (Future<Long> f : results) {
                    assertThat(f.get(60, TimeUnit.SECONDS)).as("round " + round).isEqualTo(1L);
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".store_settings", Integer.class))
                        .as("rows after round " + round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM " + schema + ".store_settings WHERE id > (SELECT min(id) FROM " + schema + ".store_settings)");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_store_settings_singleton ON " + schema + ".store_settings ((TRUE))");
        }
    }

    @Test
    void v9UniqueIndexMakesASecondSingletonRowImpossible() {
        get(a); // make sure the row exists
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + a.schemaName()
                + ".store_settings (schema_version, theme, version, created_at) VALUES (1, '{}'::jsonb, 1, now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + a.schemaName() + ".store_settings", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC3 invalid documents end-to-end

    @Test
    void invalidDocumentReturnsFieldErrorsAndStoresNothing() {
        ThemeSnapshot before = get(a);
        ObjectNode bad = defaults();
        ObjectNode props = (ObjectNode) section(bad, "hero").get("props");
        props.put("heading", "h".repeat(81));
        props.put("subheading", "s".repeat(201));
        bad.with("assets").putObject("logo").put("alt", "x").put("url", "https://evil.example/logo.png");
        bad.put("surprise", 1);
        assertThatThrownBy(() -> put(a, bad, null)).isInstanceOfSatisfying(ThemeValidationException.class, e ->
                assertThat(e.getErrors()).extracting(ThemeFieldError::path)
                        .contains("pages.home[0].props.heading", "pages.home[0].props.subheading", "assets.logo.url", "surprise"));
        assertThat(dbVersion(a)).isEqualTo(before.version());
    }

    @Test
    void oversizedPayloadIsRejectedAndStoresNothing() {
        ThemeSnapshot before = get(a);
        ObjectNode big = defaults();
        ArrayNode home = (ArrayNode) big.get("pages").get("home");
        ((ObjectNode) home.get(0).get("props")).put("subheading", "x".repeat(200));
        big.put("padding", "y".repeat(70_000));
        assertThatThrownBy(() -> put(a, big, null)).isInstanceOf(PayloadTooLargeException.class);
        assertThat(dbVersion(a)).isEqualTo(before.version());
    }

    @Test
    void xssPayloadsAreRejectedOrDroppedAndNeverPersisted() {
        ThemeSnapshot before = get(a);
        for (String text : new String[]{"<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "x</style><script>"}) {
            ObjectNode d = defaults();
            ((ObjectNode) section(d, "hero").get("props")).put("heading", text);
            assertThatThrownBy(() -> put(a, d, null)).as(text).isInstanceOf(ThemeValidationException.class);
        }
        assertThat(dbVersion(a)).isEqualTo(before.version());
        // SF-BE-01: rich-text sections are no longer storable at all: whatever the body, it is dropped, never persisted
        String[] bodies = {"<script>alert(1)</script>", "[x](javascript:alert(1))", "[x](JaVa\nScRiPt:alert(1))",
                "[x](&#106;avascript:alert(1))", "[x](data:text/html;base64,PHNjcmlwdD4=)", "![x](https://res.cloudinary.com/a/image/upload/b.png)",
                "<img src=x onerror=alert(1)>", "&lt;script&gt;"};
        for (String body : bodies) {
            ObjectNode d = defaults();
            ((ArrayNode) d.get("pages").get("home")).add(json("{\"id\":\"rt\",\"type\":\"rich-text\",\"props\":{\"body\":" + MAPPER.valueToTree(body) + "},\"visible\":true,\"order\":5}"));
            ThemeSnapshot saved = put(a, d, null);
            assertThat(saved.theme().toString()).as(body).doesNotContain("rich-text").doesNotContain("javascript").doesNotContain("<script");
        }
        assertThat(jdbc.queryForObject("SELECT theme::text FROM " + a.schemaName() + ".store_settings", String.class))
                .doesNotContain("<script").doesNotContain("javascript:").doesNotContain("rich-text");
    }

    // ---------------------------------------------------------------- references

    private long newCategory(TestTenant t, String slug) {
        return tx(t, () -> categories.save(Category.builder().name(slug).slug(slug).build()).getId());
    }

    private long newProduct(TestTenant t, String slug) {
        return tx(t, () -> products.save(Product.builder().name(slug).slug(slug).sku("SKU-" + slug).shortDescription("s")
                .price(new Money(new BigDecimal("10.00"), CurrencyCode.GHS)).stockQuantity(1).isActive(true).isEnlisted(true).build()).getId());
    }

    private ObjectNode withRefs(long categoryId, long... productIds) {
        ObjectNode d = defaults();
        ArrayNode home = (ArrayNode) d.get("pages").get("home");
        String ids = java.util.Arrays.toString(productIds);
        home.add(json("{\"id\":\"pick\",\"type\":\"featured-products\",\"props\":{\"source\":\"manual\",\"productIds\":" + ids + "},\"visible\":true,\"order\":5}"));
        home.add(json("{\"id\":\"cat\",\"type\":\"featured-products\",\"props\":{\"source\":\"category\",\"categoryId\":" + categoryId + "},\"visible\":true,\"order\":6}"));
        return d;
    }

    @Test
    void featuredAndCategorySectionsAreDroppedSoReferencesAreNeverStored_orLookedUp() {
        long cat = newCategory(a, "t02-cat-" + System.nanoTime());
        long prod = newProduct(a, "t02-prod-" + System.nanoTime());
        ThemeSnapshot ok = put(a, withRefs(cat, prod), null);
        assertThat(ok.theme().at("/pages/home")).hasSize(1);
        assertThat(ok.theme().toString()).doesNotContain("featured-products").doesNotContain("productIds");
        // ids that exist nowhere used to be 400 UNKNOWN_REFERENCE; the sections are dropped now, so the PUT is a plain 200
        ThemeSnapshot unknown = put(a, withRefs(cat + 1000, prod + 1000), null);
        assertThat(unknown.theme().toString()).doesNotContain("featured-products");
    }

    @Test
    void anotherTenantsProductIdsAreNeverStoredEither() {
        long cat = newCategory(a, "t02x-cat-" + System.nanoTime());
        long bOnly = newProduct(b, "t02x-prod-" + System.nanoTime());
        ThemeSnapshot saved = put(a, withRefs(cat, bOnly), null);
        assertThat(saved.theme().toString()).doesNotContain("featured-products").doesNotContain(String.valueOf(bOnly) + "]");
        assertThat(saved.theme().at("/pages/home")).hasSize(1);
    }

    // ---------------------------------------------------------------- AC2 uploads end-to-end

    @Test
    void logoUploadStoresUnderTenantFolderAndPersistsSecureUrl() {
        ObjectNode doc = defaults();
        doc.with("assets").putObject("logo").put("alt", "Theme A");
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(64, 64), "image/png", "whatever.exe"), null, Map.of());
        ThemeSnapshot saved = as(a, () -> themes.updateTheme(doc, null, up, "admin"));
        assertThat(storage.uploads).singleElement().asString().matches("shopfi/themea/branding/logo-[0-9a-f]{16}");
        assertThat(saved.theme().at("/assets/logo/url").asText()).matches("https://res\\.cloudinary\\.com/test/image/upload/v1/shopfi/themea/branding/logo-[0-9a-f]{16}\\.png");
        assertThat(get(a).theme().at("/assets/logo/url").asText()).contains("/shopfi/themea/branding/");
        // B never sees it
        assertThat(get(b).theme().at("/assets/logo").isNull()).isTrue();
    }

    @Test
    void spoofedOrOversizedUploadsPersistNothing() {
        ThemeSnapshot before = get(a);
        ObjectNode doc = defaults();
        doc.with("assets").putObject("logo").put("alt", "Theme A");
        ThemeUploads svg = new ThemeUploads(new BrandingFile("<svg onload=alert(1)/>".getBytes(), "image/png", "logo.png"), null, Map.of());
        assertThatThrownBy(() -> as(a, () -> themes.updateTheme(doc, null, svg, "x"))).isInstanceOf(ThemeValidationException.class);
        byte[] big = java.util.Arrays.copyOf(FakeBrandingStorage.png(10, 10), 3 * 1024 * 1024);
        ThemeUploads huge = new ThemeUploads(new BrandingFile(big, "image/png", "logo.png"), null, Map.of());
        assertThatThrownBy(() -> as(a, () -> themes.updateTheme(doc, null, huge, "x"))).isInstanceOf(PayloadTooLargeException.class);
        assertThat(storage.uploads).isEmpty();
        assertThat(dbVersion(a)).isEqualTo(before.version());
    }

    @Test
    void foreignTenantAssetUrlCannotBeAttached() {
        ObjectNode doc = defaults();
        doc.with("assets").putObject("logo").put("alt", "x")
                .put("url", "https://res.cloudinary.com/test/image/upload/v1/shopfi/themeb/branding/logo.png");
        assertThatThrownBy(() -> put(a, doc, null)).isInstanceOfSatisfying(ThemeValidationException.class, e ->
                assertThat(e.getErrors()).anyMatch(x -> x.code().equals("ASSET_URL_NOT_OWNED") && x.path().equals("assets.logo.url")));
    }

    @Test
    void updateWithoutTenantFails() {
        assertThatThrownBy(() -> themes.updateTheme(defaults(), null)).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- THEME-06 AC2: no dangling asset after a concurrent write

    private String storeLogo(TestTenant t) {
        ObjectNode doc = defaults();
        doc.with("assets").putObject("logo").put("alt", "Theme");
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(64, 64), "image/png", "l.png"), null, Map.of());
        return as(t, () -> themes.updateTheme(doc, null, up, "admin")).theme().at("/assets/logo/url").asText();
    }

    private ObjectNode withLogoUrl(String url) {
        ObjectNode doc = defaults();
        doc.with("assets").putObject("logo").put("alt", "Theme").put("url", url);
        return doc;
    }

    private String logoKey(String url) {
        return url.substring(url.indexOf("shopfi/")).replaceAll("\\.png$", "");
    }

    /**
     * Writer W1 (no If-Match) validated a document that keeps the stored logo; before W1 saves, writer W2 removes the logo
     * (its post-commit cleanup deletes the asset). W1's save must be a 409-style conflict, not a theme pointing at a deleted
     * asset.
     */
    @Test
    void aWriterWithoutIfMatchCannotSaveAThemePointingAtAnAssetAConcurrentWriterRemoved() {
        String logo = storeLogo(a);
        java.util.concurrent.atomic.AtomicBoolean competed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            if (competed.compareAndSet(false, true)) {
                var w1Tenant = com.example.perfume_budget.tenancy.TenantContext.get();
                put(a, defaults(), null); // W2: no logo -> the old asset is deleted after its commit
                com.example.perfume_budget.tenancy.TenantContext.set(w1Tenant); // runAs cleared W1's context; W1 goes on
            }
            return inv.callRealMethod();
        }).when(validatorSpy).validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> put(a, withLogoUrl(logo), null)).isInstanceOf(ThemeConflictException.class);

        assertThat(competed).isTrue();
        assertThat(storage.objects).as("W2's cleanup deleted the asset").doesNotContainKey(logoKey(logo));
        assertThat(get(a).theme().at("/assets/logo").isNull()).as("the stored theme is W2's, with no logo").isTrue();
        assertThat(dbPrimary(a)).isNotNull();
    }

    @Test
    void anUnrelatedConcurrentEditStaysLastWriteWins_whenTheAssetIsStillStored() {
        String logo = storeLogo(a);
        java.util.concurrent.atomic.AtomicBoolean competed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            if (competed.compareAndSet(false, true)) {
                ObjectNode w2 = withLogoUrl(logo);
                w2.put("themeId", "ocean");
                var w1Tenant = com.example.perfume_budget.tenancy.TenantContext.get();
                put(a, w2, null); // W2 changes a colour and keeps the logo
                com.example.perfume_budget.tenancy.TenantContext.set(w1Tenant); // runAs cleared W1's context; W1 goes on
            }
            return inv.callRealMethod();
        }).when(validatorSpy).validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        ThemeSnapshot saved = put(a, withLogoUrl(logo), null); // W1 (stale colours, logo kept): last write wins

        assertThat(competed).isTrue();
        assertThat(saved.theme().at("/assets/logo/url").asText()).isEqualTo(logo);
        assertThat(storage.objects).containsKey(logoKey(logo));
    }

    @Test
    void storeSaveRejectsADocumentReferencingAValidatedUrlThatIsNoLongerStored() {
        String logo = storeLogo(a);
        put(a, defaults(), null); // the logo is gone from the stored theme
        assertThatThrownBy(() -> as(a, () -> themeStore.save(withLogoUrl(logo), null, "w1", java.util.Set.of(logo))))
                .isInstanceOf(ThemeConflictException.class);
        // a document that does not reference it, or a URL that was never in the validated set, is not affected
        assertThat(as(a, () -> themeStore.save(defaults(), null, "w1", java.util.Set.of(logo))).version()).isGreaterThan(1);
        assertThat(as(a, () -> themeStore.save(defaults(), null, "w1", java.util.Set.of())).version()).isGreaterThan(1);
    }
}

package com.example.perfume_budget.theme;

import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SF-BE-01 through the full filter chain on Postgres: catalog themes, PUT canonicalization, the in-memory legacy
 * resolver on GET, error codes, ETag/If-Match, tenant isolation and authorization. Own tenants (thmcata, thmcatb); every
 * test starts from a known stored document.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://thmcata.shopfi.test,https://thmcatb.shopfi.test"
})
class ThemeCatalogHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeBrandingStorage fakeBrandingStorage() {
            return new FakeBrandingStorage("test");
        }

        @Bean
        @Primary
        ThemeWriteRateLimiter frozenClockLimiter() {
            return new ThemeWriteRateLimiter(1000, new ThemeWriteRateLimiterTest.MutableClock());
        }
    }

    static final String A = "https://thmcata.shopfi.test";
    static final String B = "https://thmcatb.shopfi.test";
    static final String URL = "/api/v1/theme";
    static final String MORROW_PRIMARY = "#35432f";

    @Autowired MockMvc mvc;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @Autowired UserRepository users;
    @Autowired FakeBrandingStorage storage;
    @Autowired ThemeWriteRateLimiter limiter;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;
    @Autowired ThemeCatalog catalog;

    TestTenant a;
    TestTenant b;
    String adminA;
    String adminB;
    String customerA;
    static JsonSchema schema;

    @BeforeEach
    void setUp() throws IOException {
        a = tenants.ensureTenant("thmcata");
        b = tenants.ensureTenant("thmcatb");
        storage.reset();
        limiter.reset();
        seed(a, defaults());
        seed(b, defaults());
        adminA = token(a, a.adminEmail(), "ADMIN", 1L);
        adminB = token(b, b.adminEmail(), "ADMIN", 1L);
        String custEmail = "cust@thmcata.test";
        tenants.runAs(a, () -> {
            if (users.findByEmail(custEmail).isEmpty()) {
                users.save(User.builder().fullName("Cust").email(custEmail).password("x").roles(UserRole.CUSTOMER)
                        .isActive(true).emailVerified(true).build());
            }
        });
        customerA = token(a, custEmail, "CUSTOMER", 99L);
        if (schema == null) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Files.readString(Path.of("docs", "api", "theme.schema.json")));
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Writes a stored document straight into the tenant's singleton row (version 1) and drops the cached copy. */
    private void seed(TestTenant t, ObjectNode stored) {
        jdbc.update("UPDATE " + t.schemaName() + ".store_settings SET theme = CAST(? AS jsonb), schema_version = 1, version = 1, "
                + "updated_by = NULL", stored.toString());
        tenants.runAs(t, () -> {
            cacheManager.getCache("theme").clear();
            return null;
        });
    }

    private String token(TestTenant t, String email, String role, Long id) {
        return tenants.runAs(t, (Supplier<String>) () -> jwtUtil.generateAccessToken(email, role, id, "n"));
    }

    private static Cookie auth(String token) {
        return new Cookie("accessToken", token);
    }

    private MockHttpServletRequestBuilder putJson(String origin, String token, String body) {
        MockHttpServletRequestBuilder r = put(URL).header("Origin", origin).contentType("application/json").content(body);
        return token == null ? r : r.cookie(auth(token));
    }

    private MockMultipartHttpServletRequestBuilder putMultipart(String origin, String token) {
        MockMultipartHttpServletRequestBuilder r = multipart(HttpMethod.PUT, URL);
        r.header("Origin", origin);
        if (token != null) {
            r.cookie(auth(token));
        }
        return r;
    }

    private MvcResult fetch(String origin) throws Exception {
        return mvc.perform(get(URL).header("Origin", origin)).andReturn();
    }

    private static JsonNode data(MvcResult r) throws Exception {
        return json(r.getResponse().getContentAsString()).get("data");
    }

    /** The contract's acme document as a client would PUT it to ANOTHER tenant: its image URLs belong to acme, so drop them. */
    private static ObjectNode acmeForPut() {
        ObjectNode d = acme();
        ((ObjectNode) d.get("assets")).putNull("logo");
        ((ObjectNode) section(d, "hero").get("props")).remove("imageUrl");
        return d;
    }

    private MvcResult putOk(String origin, String tok, ObjectNode doc) throws Exception {
        MvcResult r = mvc.perform(putJson(origin, tok, doc.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return r;
    }

    private void assertSchemaValid(JsonNode theme) {
        assertThat(schema.validate(theme)).as("theme.schema.json violations").isEmpty();
    }

    private String dbTheme(TestTenant t) {
        return jdbc.queryForObject("SELECT theme::text FROM " + t.schemaName() + ".store_settings", String.class);
    }

    private long dbVersion(TestTenant t) {
        return jdbc.queryForObject("SELECT version FROM " + t.schemaName() + ".store_settings", Long.class);
    }

    private static long versionOf(MvcResult r) {
        return Long.parseLong(r.getResponse().getHeader("ETag").replaceAll("\\D", ""));
    }

    private static JsonNode withoutStamps(JsonNode doc) {
        ObjectNode c = doc.deepCopy();
        c.remove("revision");
        c.remove("updatedAt");
        return c;
    }

    // ------------------------------------------------------------------------------------------ GET: resolver

    @Test
    void get_untouchedDefaultResolvesToMorrow_inMemory_withoutWriting() throws Exception {
        String before = dbTheme(a);
        MvcResult r = fetch(A);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.get("themeId").asText()).isEqualTo("morrow");
        assertThat(d.get("tokens")).isEqualTo(catalog.tokens("morrow"));
        assertThat(d.at("/tokens/colors/primary").asText()).isEqualTo(MORROW_PRIMARY);
        assertSchemaValid(d);
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        assertThat(dbTheme(a)).as("GET never writes").isEqualTo(before);
        assertThat(dbVersion(a)).isEqualTo(1);
        assertThat(dbTheme(a)).doesNotContain("themeId");
    }

    @Test
    void get_customisedLegacyDocumentIsCustomLegacyAndKeepsItsOwnTokens() throws Exception {
        ObjectNode legacy = acme();
        seed(a, legacy);
        String before = dbTheme(a);
        MvcResult r = fetch(A);
        JsonNode d = data(r);
        assertThat(d.get("themeId").asText()).isEqualTo("custom-legacy");
        assertThat(d.get("tokens")).isEqualTo(legacy.get("tokens"));
        assertSchemaValid(d);
        assertThat(dbTheme(a)).isEqualTo(before);
        // the other tenant is unaffected
        assertThat(data(fetch(B)).get("themeId").asText()).isEqualTo("morrow");
    }

    @Test
    void get_legacyDocumentWhoseColoursEqualACatalogThemeResolvesToIt() throws Exception {
        ObjectNode stored = defaults();
        stored.set("tokens", catalog.tokens("sand"));
        seed(a, stored);
        assertThat(data(fetch(A)).get("themeId").asText()).isEqualTo("sand");
    }

    // ------------------------------------------------------------------------------------------ PUT: catalog

    @Test
    void put_themeId_storesThatThemesTokens_whateverTokensWereSent_andGetReturnsThem() throws Exception {
        ObjectNode d = acmeForPut();
        d.put("themeId", "ocean");
        ((ObjectNode) d.at("/tokens/colors")).put("primary", "#ff0000").put("text", "#ffffff");
        MvcResult r = putOk(A, adminA, d);
        JsonNode stored = data(r);
        assertThat(stored.get("themeId").asText()).isEqualTo("ocean");
        assertThat(stored.get("tokens")).isEqualTo(catalog.tokens("ocean"));
        assertSchemaValid(stored);
        assertThat(versionOf(r)).isEqualTo(2);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");

        MvcResult g = fetch(A);
        assertThat(data(g)).isEqualTo(stored);
        assertThat(g.getResponse().getHeader("ETag")).isEqualTo(r.getResponse().getHeader("ETag"));
        assertThat(jdbc.queryForObject("SELECT theme->'tokens'->'colors'->>'primary' FROM " + a.schemaName() + ".store_settings", String.class))
                .isEqualTo("#0f3d5c");
    }

    @Test
    void put_everyCatalogThemeCanBeSelectedAndReadBack() throws Exception {
        for (String id : catalog.ids()) {
            ObjectNode d = defaults();
            d.put("themeId", id);
            JsonNode stored = data(putOk(A, adminA, d));
            assertThat(stored.get("themeId").asText()).isEqualTo(id);
            assertThat(data(fetch(A)).get("tokens")).as(id).isEqualTo(catalog.tokens(id));
        }
    }

    @Test
    void put_unknownThemeId_is400_withStableCode_andChangesNothing() throws Exception {
        String before = dbTheme(a);
        for (String bad : new String[]{"does-not-exist", "Morrow", "custom-legacy", "", "../../etc"}) {
            ObjectNode d = defaults();
            d.put("themeId", bad);
            MvcResult r = mvc.perform(putJson(A, adminA, d.toString())).andReturn();
            assertThat(r.getResponse().getStatus()).as("[%s]", bad).isEqualTo(400);
            JsonNode body = json(r.getResponse().getContentAsString());
            assertThat(body.get("description").asText()).isEqualTo("Theme validation failed");
            assertThat(body.at("/data/0/path").asText()).isEqualTo("themeId");
            assertThat(body.at("/data/0/code").asText()).isEqualTo("NOT_ALLOWED_VALUE");
            assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        ObjectNode wrongType = defaults();
        wrongType.put("themeId", 7);
        MvcResult r = mvc.perform(putJson(A, adminA, wrongType.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r.getResponse().getContentAsString()).at("/data/0/code").asText()).isEqualTo("WRONG_TYPE");
        assertThat(dbTheme(a)).isEqualTo(before);
        assertThat(dbVersion(a)).isEqualTo(1);
        // multipart goes through the same check and uploads nothing
        ObjectNode bad = defaults();
        bad.put("themeId", "nope");
        MvcResult mp = mvc.perform(putMultipart(A, adminA).file(new MockMultipartFile("theme", "t.json", "application/json", bad.toString().getBytes()))
                .file(new MockMultipartFile("logo", "l.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
        assertThat(mp.getResponse().getStatus()).isEqualTo(400);
        assertThat(storage.uploads).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ PUT: canonicalization

    @Test
    void put_oldClientFullDocument_stillWorks_andIsCanonicalized() throws Exception {
        ObjectNode old = acmeForPut(); // banner, rich-text, featured, category, footer, background, custom tokens, no themeId
        MvcResult r = putOk(A, adminA, old);
        JsonNode s = data(r);
        assertSchemaValid(s);
        assertThat(s.get("themeId").asText()).as("old client: the tenant's current theme is kept").isEqualTo("morrow");
        assertThat(s.get("tokens")).as("tokens the client sent are ignored").isEqualTo(catalog.tokens("morrow"));
        assertThat(s.at("/pages/home")).hasSize(2); // hero + announcement (owner decision 2026-11-24)
        assertThat(s.at("/pages/home/0/id").asText()).isEqualTo("hero");
        assertThat(s.at("/pages/home/1/id").asText()).isEqualTo("announcement");
        assertThat(s.at("/pages/product")).isEmpty();
        assertThat(s.at("/pages/footer")).hasSize(1);
        assertThat(s.at("/pages/footer/0/props").fieldNames()).toIterable().containsExactly("linkGroups");
        assertThat(s.at("/assets/background").isNull()).isTrue();
        assertThat(s.at("/assets/logo").isNull()).isTrue();
        JsonNode oh = section(old, "hero").get("props");
        for (String k : new String[]{"heading", "subheading", "imageAlt", "ctaLabel"}) {
            assertThat(s.at("/pages/home/0/props/" + k)).as(k).isEqualTo(oh.get(k));
        }
        assertThat(s.at("/pages/home/0/props/ctaHref").asText()).isEqualTo("/products");
        assertThat(s.at("/pages/home/0/props/alignment").asText()).isEqualTo("left");
        assertThat(s.at("/pages/home/0/props").has("overlayOpacity")).isFalse();
        assertThat(dbTheme(a)).doesNotContain("rich-text").doesNotContain("featured-products")
                .doesNotContain("category-grid").doesNotContain("overlayOpacity").doesNotContain("Fragrance, simply.");
    }

    @Test
    void put_heroTextAndPhotoAndLogoAreKept_viaMultipart() throws Exception {
        ObjectNode d = defaults();
        d.put("themeId", "blush");
        ObjectNode props = (ObjectNode) section(d, "hero").get("props");
        props.put("heading", "Fresh scents").put("subheading", "Small batch").put("ctaLabel", "Browse").put("imageAlt", "Bottles");
        d.with("assets").putObject("logo").put("alt", "Thmcata");
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(new MockMultipartFile("theme", "t.json", "application/json", d.toString().getBytes()))
                .file(new MockMultipartFile("logo", "x.png", "image/png", FakeBrandingStorage.png(40, 40)))
                .file(new MockMultipartFile("background", "b.jpg", "image/jpeg", FakeBrandingStorage.jpeg(100, 80)))
                .file(new MockMultipartFile("image.hero", "h.webp", "image/webp", FakeBrandingStorage.webpLossy(50, 50)))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode s = data(r);
        assertThat(s.at("/pages/home/0/props/heading").asText()).isEqualTo("Fresh scents");
        assertThat(s.at("/pages/home/0/props/subheading").asText()).isEqualTo("Small batch");
        assertThat(s.at("/pages/home/0/props/ctaLabel").asText()).isEqualTo("Browse");
        assertThat(s.at("/pages/home/0/props/imageAlt").asText()).isEqualTo("Bottles");
        assertThat(s.at("/pages/home/0/props/imageUrl").asText()).contains("/shopfi/thmcata/branding/section-hero");
        assertThat(s.at("/assets/logo/url").asText()).contains("/shopfi/thmcata/branding/logo");
        assertThat(s.at("/assets/background").isNull()).isTrue();
        assertThat(storage.uploads).hasSize(2); // logo + hero; the background part is ignored, nothing uploaded for it
        assertSchemaValid(s);
    }

    @Test
    void put_heroImagePartNamedAfterTheSubmittedHeroId_followsTheCanonicalHero() throws Exception {
        ObjectNode d = defaults();
        section(d, "hero").put("id", "main-hero");
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(new MockMultipartFile("theme", "t.json", "application/json", d.toString().getBytes()))
                .file(new MockMultipartFile("image.main-hero", "h.webp", "image/webp", FakeBrandingStorage.webpLossy(50, 50)))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(data(r).at("/pages/home/0/id").asText()).isEqualTo("hero");
        assertThat(data(r).at("/pages/home/0/props/imageUrl").asText()).contains("section-hero");
    }

    @Test
    void put_invalidBrandingStillFails_with400AndNoChange() throws Exception {
        String before = dbTheme(a);
        ObjectNode d = defaults();
        ObjectNode props = (ObjectNode) section(d, "hero").get("props");
        props.put("heading", "h".repeat(81)).put("subheading", "<b>x</b>").put("ctaLabel", "c".repeat(31));
        d.with("assets").putObject("logo").put("alt", "x").put("url", "https://evil.example/l.png");
        MvcResult r = mvc.perform(putJson(A, adminA, d.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("pages.home[0].props.heading").contains("pages.home[0].props.subheading")
                .contains("pages.home[0].props.ctaLabel").contains("assets.logo.url");
        assertThat(dbTheme(a)).isEqualTo(before);
    }

    @Test
    void put_idempotentRePut_ofTheReturnedDocument_changesNothingButTheRevision() throws Exception {
        ObjectNode d = acmeForPut();
        d.put("themeId", "plum");
        JsonNode first = data(putOk(A, adminA, d));
        JsonNode second = data(putOk(A, adminA, (ObjectNode) first.deepCopy()));
        JsonNode third = data(putOk(A, adminA, (ObjectNode) second.deepCopy()));
        assertThat(withoutStamps(second)).isEqualTo(withoutStamps(first));
        assertThat(withoutStamps(third)).isEqualTo(withoutStamps(first));
        assertThat(second.get("revision").asInt()).isEqualTo(first.get("revision").asInt() + 1);
        assertThat(third.get("revision").asInt()).isEqualTo(first.get("revision").asInt() + 2);
    }

    // ------------------------------------------------------------------------------------------ legacy tenants

    @Test
    void legacyTenant_oldClientPutKeepsItsColours_chosenThemeReplacesThem_andLegacyCannotBeSubmitted() throws Exception {
        ObjectNode legacy = acme();
        seed(a, legacy);
        // an old client saves without themeId: the tenant's own saved colours survive
        JsonNode kept = data(putOk(A, adminA, defaults()));
        assertThat(kept.get("themeId").asText()).isEqualTo("custom-legacy");
        assertThat(kept.get("tokens")).isEqualTo(legacy.get("tokens"));
        assertThat(data(fetch(A)).get("themeId").asText()).isEqualTo("custom-legacy");
        // the editor round-trip of the legacy id is refused
        ObjectNode roundTrip = (ObjectNode) kept.deepCopy();
        MvcResult refused = mvc.perform(putJson(A, adminA, roundTrip.toString())).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(refused.getResponse().getContentAsString()).at("/data/0/code").asText()).isEqualTo("NOT_ALLOWED_VALUE");
        // choosing a catalog theme replaces the legacy colours, and there is no way back
        ObjectNode pick = defaults();
        pick.put("themeId", "slate");
        assertThat(data(putOk(A, adminA, pick)).get("tokens")).isEqualTo(catalog.tokens("slate"));
        ObjectNode back = defaults();
        back.put("themeId", "custom-legacy");
        assertThat(mvc.perform(putJson(A, adminA, back.toString())).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(data(fetch(A)).get("themeId").asText()).isEqualTo("slate");
    }

    // ------------------------------------------------------------------------------------------ versioning

    @Test
    void ifMatch_staleIs412_currentIs200_andEachPutBumpsTheVersionOnce() throws Exception {
        ObjectNode d = defaults();
        d.put("themeId", "ocean");
        MvcResult stale = mvc.perform(putJson(A, adminA, d.toString()).header("If-Match", "W/\"7\"")).andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        assertThat(dbVersion(a)).isEqualTo(1);
        assertThat(dbTheme(a)).doesNotContain("ocean");
        MvcResult ok = mvc.perform(putJson(A, adminA, d.toString()).header("If-Match", "W/\"1\"")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(mvc.perform(putJson(A, adminA, d.toString()).header("If-Match", "W/\"1\"")).andReturn().getResponse().getStatus()).isEqualTo(412);
        assertThat(dbVersion(a)).isEqualTo(2);
    }

    @Test
    void concurrentPutsWithTheSameIfMatch_exactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (String id : new String[]{"ocean", "plum"}) {
                fs.add(pool.submit(() -> {
                    go.await();
                    ObjectNode d = defaults();
                    d.put("themeId", id);
                    return mvc.perform(putJson(A, adminA, d.toString()).header("If-Match", "W/\"1\"")).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 412);
            assertThat(dbVersion(a)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------ isolation and authorization

    @Test
    void tenantsNeverSeeEachOthersTheme() throws Exception {
        ObjectNode d = defaults();
        d.put("themeId", "ocean");
        putOk(A, adminA, d);
        assertThat(data(fetch(A)).get("themeId").asText()).isEqualTo("ocean");
        assertThat(data(fetch(B)).get("themeId").asText()).isEqualTo("morrow");
        assertThat(data(mvc.perform(get(URL).header("Referer", B + "/x")).andReturn()).get("tokens")).isEqualTo(catalog.tokens("morrow"));
        ObjectNode e = defaults();
        e.put("themeId", "plum");
        putOk(B, adminB, e);
        assertThat(data(fetch(A)).get("themeId").asText()).isEqualTo("ocean");
        assertThat(data(fetch(B)).get("themeId").asText()).isEqualTo("plum");
        // an admin of A cannot write B's theme, with or without themeId
        assertThat(mvc.perform(putJson(B, adminA, e.toString())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(dbTheme(b)).contains("\"themeId\": \"plum\"");
    }

    @Test
    void themeIdDoesNotBypassAuthorization() throws Exception {
        ObjectNode d = defaults();
        d.put("themeId", "ocean");
        assertThat(mvc.perform(putJson(A, null, d.toString())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(putJson(A, customerA, d.toString())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(dbTheme(a)).doesNotContain("ocean");
        assertThat(dbVersion(a)).isEqualTo(1);
        // the public GET needs no credentials and ignores a foreign cookie
        assertThat(data(mvc.perform(get(URL).header("Origin", A).cookie(auth(adminB))).andReturn()).get("themeId").asText()).isEqualTo("morrow");
    }

    @Test
    void responseNeverCarriesAnythingButTheDocument() throws Exception {
        JsonNode d = data(fetch(A));
        assertThat(d.fieldNames()).toIterable().containsExactlyInAnyOrder("schemaVersion", "revision", "updatedAt", "themeId", "tokens", "assets", "pages")
                .as("revision and updatedAt are stamped by the store");
        ArrayNode home = (ArrayNode) d.at("/pages/home");
        assertThat(home).isNotNull();
    }

    // ------------------------------------------------------------------------------------------ announcement, story, footer links, social

    private ObjectNode brandingDoc() {
        ObjectNode d = defaults();
        d.put("themeId", "ocean");
        ArrayNode home = (ArrayNode) d.at("/pages/home");
        home.removeAll();
        home.add(json("{\"id\":\"hero\",\"type\":\"hero\",\"props\":{\"heading\":\"Hi\"},\"visible\":true,\"order\":0}"));
        home.add(json("{\"id\":\"a\",\"type\":\"banner\",\"props\":{\"message\":\"Free delivery over GHS 500\",\"linkLabel\":\"Shop\",\"linkHref\":\"/products\"},\"visible\":true,\"order\":1}"));
        home.add(json("{\"id\":\"s\",\"type\":\"story\",\"props\":{\"heading\":\"Our story\",\"body\":\"We make scents.\\n\\nSince 2020.\"},\"visible\":true,\"order\":2}"));
        ((ArrayNode) d.at("/pages/footer")).removeAll();
        ((ArrayNode) d.at("/pages/footer")).add(json("{\"id\":\"footer\",\"type\":\"footer\",\"props\":{\"linkGroups\":[{\"title\":\"Help\",\"links\":[{\"label\":\"Delivery\",\"href\":\"/delivery\"}]}]},\"visible\":true,\"order\":0}"));
        d.putObject("social").put("instagram", "https://www.instagram.com/shop").put("x", "https://twitter.com/shop").put("youtube", "https://www.youtube.com/@shop");
        return d;
    }

    @Test
    void put_announcementStoryFooterLinksAndSocial_roundTrip_andIdempotent() throws Exception {
        JsonNode s = data(putOk(A, adminA, brandingDoc()));
        assertSchemaValid(s);
        assertThat(s.at("/pages/home")).hasSize(3);
        assertThat(s.at("/pages/home/1/props/message").asText()).isEqualTo("Free delivery over GHS 500");
        assertThat(s.at("/pages/home/2/props/body").asText()).isEqualTo("We make scents.\n\nSince 2020.");
        assertThat(s.at("/pages/footer/0/props/linkGroups/0/links/0/href").asText()).isEqualTo("/delivery");
        assertThat(s.at("/social/x").asText()).isEqualTo("https://twitter.com/shop");
        assertThat(data(fetch(A))).isEqualTo(s);
        JsonNode again = data(putOk(A, adminA, (ObjectNode) s.deepCopy()));
        assertThat(withoutStamps(again)).isEqualTo(withoutStamps(s));
        // tenant B never sees A's branding
        JsonNode b = data(fetch(B));
        assertThat(b.at("/social").isMissingNode() || b.at("/social").isEmpty()).isTrue();
        assertThat(b.toString()).doesNotContain("Our story").doesNotContain("instagram.com/shop");
    }

    @Test
    void put_oldClientThatDoesNotKnowSocial_keepsTheStoredSocialLinks() throws Exception {
        putOk(A, adminA, brandingDoc());
        ObjectNode old = brandingDoc();
        old.remove("social");
        assertThat(data(putOk(A, adminA, old)).at("/social/instagram").asText()).isEqualTo("https://www.instagram.com/shop");
        ObjectNode cleared = brandingDoc();
        cleared.putObject("social");
        assertThat(data(putOk(A, adminA, cleared)).at("/social").size()).isZero();
    }

    private JsonNode firstError(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        return json(r.getResponse().getContentAsString()).at("/data/0");
    }

    @Test
    void put_capsAndSocialRules_return400WithStableCodes_andChangeNothing() throws Exception {
        String before = dbTheme(a);
        ObjectNode d = brandingDoc();
        ((ObjectNode) d.at("/pages/home/2/props")).put("body", "b".repeat(601));
        JsonNode e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("pages.home[2].props.body");
        assertThat(e.get("code").asText()).isEqualTo("TOO_LONG");
        ((ObjectNode) d.at("/pages/home/2/props")).put("body", "b".repeat(600)); // exactly the cap is fine
        assertThat(mvc.perform(putJson(A, adminA, d.toString())).andReturn().getResponse().getStatus()).isEqualTo(200);
        seed(a, defaults());

        d = brandingDoc();
        ((ObjectNode) d.at("/pages/home/1/props")).put("message", "m".repeat(161));
        e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("pages.home[1].props.message");
        assertThat(e.get("code").asText()).isEqualTo("TOO_LONG");

        d = brandingDoc();
        ((ObjectNode) d.at("/pages/home/1/props")).put("linkHref", "javascript:alert(1)");
        e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("pages.home[1].props.linkHref");
        assertThat(e.get("code").asText()).isEqualTo("URL_NOT_ALLOWED");

        for (String[] bad : new String[][]{{"instagram", "https://www.facebook.com/x"}, {"facebook", "http://facebook.com/x"},
                {"tiktok", "https://user:pw@tiktok.com/x"}, {"youtube", "javascript:alert(1)"}, {"x", "https://x.com.evil.example/a"},
                {"linkedin", "https://linkedin.com:8443/in/x"},
                {"whatsapp", "https://wa.me/1\n"}}) {
            d = brandingDoc();
            ((ObjectNode) d.get("social")).put(bad[0], bad[1]);
            e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
            assertThat(e.get("path").asText()).as(bad[1]).isEqualTo("social." + bad[0]);
            assertThat(e.get("code").asText()).as(bad[1]).isEqualTo("URL_NOT_ALLOWED");
        }
        d = brandingDoc();
        ((ObjectNode) d.get("social")).put("pinterest", "https://pinterest.com/" + "p".repeat(240)); // 262 > 256
        e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("social.pinterest");
        assertThat(e.get("code").asText()).isEqualTo("TOO_LONG");
        d = brandingDoc();
        ((ObjectNode) d.get("social")).put("pinterest", "https://pinterest.com/" + "p".repeat(234)); // exactly 256
        assertThat(mvc.perform(putJson(A, adminA, d.toString())).andReturn().getResponse().getStatus()).isEqualTo(200);
        seed(a, defaults());
        d = brandingDoc();
        ((ObjectNode) d.get("social")).put("myspace", "https://myspace.com/x");
        e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("social.myspace");
        assertThat(e.get("code").asText()).isEqualTo("UNKNOWN_PROPERTY");
        d = brandingDoc();
        ((ObjectNode) d.get("social")).put("instagram", 5);
        assertThat(firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn()).get("code").asText()).isEqualTo("WRONG_TYPE");
        assertThat(dbTheme(a)).doesNotContain("myspace");
    }

    @Test
    void put_footerCaps() throws Exception {
        ObjectNode d = brandingDoc();
        ArrayNode groups = (ArrayNode) d.at("/pages/footer/0/props/linkGroups");
        for (int i = 0; i < 3; i++) {
            groups.add(json("{\"title\":\"G" + i + "\",\"links\":[{\"label\":\"L\",\"href\":\"/x\"}]}"));
        }
        assertThat(groups.size()).isEqualTo(4);
        JsonNode e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("pages.footer[0].props.linkGroups");
        assertThat(e.get("code").asText()).isEqualTo("TOO_LONG");
        d = brandingDoc();
        ((ObjectNode) d.at("/pages/footer/0/props/linkGroups/0/links/0")).put("label", "l".repeat(31));
        e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
        assertThat(e.get("path").asText()).isEqualTo("pages.footer[0].props.linkGroups[0].links[0].label");
    }

    @Test
    void put_storyHtmlAndMarkdownImagesAreRejected_notRendered() throws Exception {
        for (String body : new String[]{"<script>x</script>", "<b>x</b>", "a\u0007b"}) {
            ObjectNode d = brandingDoc();
            ((ObjectNode) d.at("/pages/home/2/props")).put("body", body);
            JsonNode e = firstError(mvc.perform(putJson(A, adminA, d.toString())).andReturn());
            assertThat(e.get("path").asText()).isEqualTo("pages.home[2].props.body");
            assertThat(e.get("code").asText()).isEqualTo("UNSAFE_CONTENT");
        }
    }
}

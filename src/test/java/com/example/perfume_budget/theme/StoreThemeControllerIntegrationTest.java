package com.example.perfume_budget.theme;

import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.function.Supplier;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * THEME-04: GET/PUT /api/v1/theme through the full filter chain (tenant resolution, size filter, JWT, security rules,
 * method security, controller advice) on Postgres. Own tenants (thmctla, thmctlb, thmctlf) so no other test class's
 * state is touched; fake Cloudinary; small Hikari pool.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://thmctla.shopfi.test,https://thmctlb.shopfi.test,https://thmctlf.shopfi.test,https://nobodyhere.shopfi.test"
})
class StoreThemeControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeBrandingStorage fakeBrandingStorage() {
            return new FakeBrandingStorage("test");
        }

        /** Frozen clock: the rate-limit test must not see tokens refill while 30 requests run. */
        @Bean
        @Primary
        ThemeWriteRateLimiter frozenClockLimiter() {
            return new ThemeWriteRateLimiter(30, new ThemeWriteRateLimiterTest.MutableClock());
        }
    }

    static final String A = "https://thmctla.shopfi.test";
    static final String B = "https://thmctlb.shopfi.test";
    static final String CACHE = "public, max-age=60, s-maxage=300, stale-while-revalidate=600";
    static final String URL = "/api/v1/theme";

    @Autowired MockMvc mvc;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @Autowired UserRepository users;
    @Autowired FakeBrandingStorage storage;
    @Autowired ThemeWriteRateLimiter limiter;
    @Autowired JdbcTemplate jdbc;

    TestTenant a;
    TestTenant b;
    TestTenant f;
    String adminA;
    String adminB;
    String customerA;
    static JsonSchema schema;

    @BeforeEach
    void setUp() throws IOException {
        a = tenants.ensureTenant("thmctla");
        b = tenants.ensureTenant("thmctlb");
        f = tenants.ensureTenant("thmctlf");
        storage.reset();
        limiter.reset();
        adminA = token(a, a.adminEmail(), "ADMIN", 1L);
        adminB = token(b, b.adminEmail(), "ADMIN", 1L);
        String custEmail = "cust@thmctla.test";
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

    private String token(TestTenant t, String email, String role, Long id) {
        return tenants.runAs(t, (Supplier<String>) () -> jwtUtil.generateAccessToken(email, role, id, "n"));
    }

    private static Cookie auth(String token) {
        return new Cookie("accessToken", token);
    }

    private ObjectNode doc(String primary) {
        ObjectNode d = defaults();
        ((ObjectNode) d.get("tokens").get("colors")).put("primary", primary).put("onPrimary", "#ffffff");
        return d;
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

    private static MockMultipartFile themePart(ObjectNode d) {
        return new MockMultipartFile("theme", "theme.json", "application/json", d.toString().getBytes());
    }

    private MvcResult fetch(String origin) throws Exception {
        return mvc.perform(get(URL).header("Origin", origin)).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return json(r.getResponse().getContentAsString()).get("data");
    }

    private String primaryOf(MvcResult r) throws Exception {
        return data(r).at("/tokens/colors/primary").asText();
    }

    private void assertSchemaValid(JsonNode theme) {
        assertThat(schema.validate(theme)).as("theme.schema.json violations").isEmpty();
    }

    private String etagOf(String origin) throws Exception {
        return fetch(origin).getResponse().getHeader("ETag");
    }

    private String dbUpdatedBy(TestTenant t) {
        return jdbc.queryForObject("SELECT updated_by FROM " + t.schemaName() + ".store_settings", String.class);
    }

    // ------------------------------------------------------------------------------------------ GET

    @Test
    void get_isPublic_returnsSsrCacheHeaders_etag_andSchemaValidEnvelope() throws Exception {
        MvcResult r = fetch(A);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo(CACHE);
        assertThat(r.getResponse().getHeaders("Vary")).anyMatch(v -> v.contains("Origin"));
        String etag = r.getResponse().getHeader("ETag");
        assertThat(etag).matches("W/\"\\d+\"");
        JsonNode body = json(r.getResponse().getContentAsString());
        assertThat(body.get("description").isNull()).isTrue();
        assertSchemaValid(body.get("data")); // AC5
        assertThat(body.at("/data/revision").asLong() + 1).isEqualTo(Long.parseLong(etag.replaceAll("\\D", "")));
    }

    @Test
    void get_freshTenantReturnsSchemaValidDefaults() throws Exception {
        MvcResult r = fetch("https://thmctlf.shopfi.test");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertSchemaValid(data(r));
        assertThat(data(r).at("/tokens/colors/primary").asText())
                .isEqualTo(defaults().at("/tokens/colors/primary").asText());
        assertThat(data(r).at("/revision").asLong()).isZero();
    }

    @Test
    void get_refererIsAlsoAccepted() throws Exception {
        MvcResult r = mvc.perform(get(URL).header("Referer", A + "/some/page")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void get_withoutOriginIsRejectedByTheFilterBeforeTheController() throws Exception {
        MvcResult r = mvc.perform(get(URL)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("tokens");
        assertThat(r.getResponse().getHeader("ETag")).isNull();
        // with a stale auth cookie but no tenant signal: 401 (existing TenantRequiredFilter rule)
        assertThat(mvc.perform(get(URL).cookie(auth(adminA))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void get_unknownTenantOrigin_is404() throws Exception {
        assertThat(mvc.perform(get(URL).header("Origin", "https://nobodyhere.shopfi.test")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void get_twoTenantsSeeDifferentThemes_andPutOnAneverChangesB() throws Exception {
        String bBefore = primaryOf(fetch(B));
        long bVersion = Long.parseLong(etagOf(B).replaceAll("\\D", ""));
        MvcResult put = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString())).andReturn();
        assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult putB = mvc.perform(putJson(B, adminB, doc("#0b3d2e").toString())).andReturn();
        assertThat(putB.getResponse().getStatus()).isEqualTo(200);

        assertThat(primaryOf(fetch(A))).isEqualTo("#7c2d12");
        assertThat(primaryOf(fetch(B))).isEqualTo("#0b3d2e").isNotEqualTo(bBefore);
        // A again: B's write did not leak into A
        assertThat(primaryOf(fetch(A))).isEqualTo("#7c2d12");
        // cache key discipline: same URL, only Origin differs
        assertThat(primaryOf(mvc.perform(get(URL).header("Referer", B + "/x")).andReturn())).isEqualTo("#0b3d2e");
        assertThat(Long.parseLong(etagOf(B).replaceAll("\\D", ""))).isEqualTo(bVersion + 1);
    }

    @Test
    void get_ifNoneMatch_304_withHeaders_andEmptyBody() throws Exception {
        String etag = etagOf(A);
        MvcResult r = mvc.perform(get(URL).header("Origin", A).header("If-None-Match", etag)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(304);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo(etag);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo(CACHE);
        assertThat(r.getResponse().getHeaders("Vary")).anyMatch(v -> v.contains("Origin"));

        assertThat(mvc.perform(get(URL).header("Origin", A).header("If-None-Match", "*")).andReturn().getResponse().getStatus()).isEqualTo(304);
        assertThat(mvc.perform(get(URL).header("Origin", A).header("If-None-Match", "W/\"9999\"")).andReturn().getResponse().getStatus()).isEqualTo(200);
        // A's ETag against tenant B's version is not a free pass: versions are compared per tenant
        mvc.perform(putJson(A, adminA, doc("#111827").toString())).andReturn();
        assertThat(mvc.perform(get(URL).header("Origin", A).header("If-None-Match", etag)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ PUT auth

    @Test
    void put_unauthenticated_is401() throws Exception {
        String before = etagOf(A);
        MvcResult r = mvc.perform(putJson(A, null, doc("#7c2d12").toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(etagOf(A)).isEqualTo(before);
    }

    @Test
    void put_customerRole_is403() throws Exception {
        MvcResult r = mvc.perform(putJson(A, customerA, doc("#7c2d12").toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        MvcResult m = mvc.perform(putMultipart(A, customerA).file(themePart(doc("#7c2d12")))).andReturn();
        assertThat(m.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void put_adminTokenOfOtherTenant_is401_andNothingChanges() throws Exception {
        String bEtag = etagOf(B);
        MvcResult r = mvc.perform(putJson(B, adminA, doc("#7c2d12").toString())).andReturn(); // A token, B origin
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        MvcResult r2 = mvc.perform(putJson(A, adminB, doc("#7c2d12").toString())).andReturn(); // B token, A origin
        assertThat(r2.getResponse().getStatus()).isEqualTo(401);
        assertThat(etagOf(B)).isEqualTo(bEtag);
        MvcResult m = mvc.perform(putMultipart(B, adminA).file(themePart(doc("#7c2d12")))).andReturn();
        assertThat(m.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void getWithCrossTenantCookie_isRejectedToo() throws Exception {
        assertThat(mvc.perform(get(URL).header("Origin", B).cookie(auth(adminA))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------------------ PUT success

    @Test
    void put_json_adminSucceeds_returnsNewSnapshot_etag_noStore_andRecordsUpdatedBy() throws Exception {
        String before = etagOf(A);
        MvcResult r = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json(r.getResponse().getContentAsString());
        assertThat(body.get("description").asText()).isEqualTo("Theme updated");
        assertThat(body.at("/data/tokens/colors/primary").asText()).isEqualTo("#7c2d12");
        assertSchemaValid(body.get("data"));
        String etag = r.getResponse().getHeader("ETag");
        assertThat(Long.parseLong(etag.replaceAll("\\D", ""))).isEqualTo(Long.parseLong(before.replaceAll("\\D", "")) + 1);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store").doesNotContain("public");
        assertThat(etagOf(A)).isEqualTo(etag);
        assertThat(dbUpdatedBy(a)).isEqualTo(a.adminEmail());
    }

    @Test
    void put_multipart_logoAndBackgroundAndHero_persistUrls_inResponse() throws Exception {
        ObjectNode d = doc("#7c2d12");
        d.with("assets").putObject("logo").put("alt", "Thmctla");
        MockMultipartFile logo = new MockMultipartFile("logo", "x.png", "image/png", FakeBrandingStorage.png(40, 40));
        MockMultipartFile bg = new MockMultipartFile("background", "b.jpg", "image/jpeg", FakeBrandingStorage.jpeg(100, 80));
        MockMultipartFile hero = new MockMultipartFile("image.hero", "h.webp", "image/webp", FakeBrandingStorage.webpLossy(50, 50));
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(themePart(d)).file(logo).file(bg).file(hero)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode t = data(r);
        assertThat(t.at("/assets/logo/url").asText()).contains("/shopfi/thmctla/branding/logo");
        assertThat(t.at("/assets/background/url").asText()).contains("/shopfi/thmctla/branding/background");
        assertThat(t.at("/pages/home/0/props/imageUrl").asText()).contains("section-hero");
        assertSchemaValid(t);
        assertThat(storage.uploads).containsExactlyInAnyOrder(
                "shopfi/thmctla/branding/logo", "shopfi/thmctla/branding/background", "shopfi/thmctla/branding/section-hero");
        // a following public GET (other tenant untouched)
        assertThat(data(fetch(A)).at("/assets/logo/url").asText()).contains("thmctla");
        JsonNode bLogo = data(fetch(B)).at("/assets/logo");
        assertThat(bLogo.at("/url").asText()).doesNotContain("thmctla");
    }

    @Test
    void put_multipart_themeAsPlainFormField_isAccepted() throws Exception {
        MvcResult r = mvc.perform(putMultipart(A, adminA).param("theme", doc("#7c2d12").toString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ If-Match

    @Test
    void put_ifMatch_stale_is412_withEnvelope_andNoChange() throws Exception {
        String current = etagOf(A);
        long v = Long.parseLong(current.replaceAll("\\D", ""));
        MvcResult stale = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString()).header("If-Match", "W/\"" + (v + 5) + "\"")).andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        JsonNode body = json(stale.getResponse().getContentAsString());
        assertThat(body.get("description").asText()).isNotBlank();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(etagOf(A)).isEqualTo(current);

        MvcResult ok = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString()).header("If-Match", current)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        // the old etag is now stale
        MvcResult again = mvc.perform(putJson(A, adminA, doc("#111827").toString()).header("If-Match", current)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(412);
        // multipart honours it as well
        MvcResult mp = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#111827"))).header("If-Match", current)).andReturn();
        assertThat(mp.getResponse().getStatus()).isEqualTo(412);
    }

    @Test
    void put_ifMatch_malformed_is400() throws Exception {
        MvcResult r = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString()).header("If-Match", "not-an-etag")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r.getResponse().getContentAsString()).at("/data/0/path").asText()).isEqualTo("If-Match");
    }

    // ------------------------------------------------------------------------------------------ errors

    @Test
    void put_validationErrors_returnStructuredList() throws Exception {
        ObjectNode d = doc("not-a-hex");
        ((ObjectNode) section(d, "hero").get("props")).put("heading", "<script>alert(1)</script>");
        MvcResult r = mvc.perform(putJson(A, adminA, d.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(r.getResponse().getContentAsString());
        assertThat(body.get("description").asText()).isEqualTo("Theme validation failed");
        assertThat(body.get("data").isArray()).isTrue();
        assertThat(body.get("data").size()).isGreaterThanOrEqualTo(2);
        for (JsonNode e : body.get("data")) {
            assertThat(e.get("path").asText()).isNotBlank();
            assertThat(e.get("code").asText()).matches("[A-Z_]+");
            assertThat(e.get("message").asText()).isNotBlank();
        }
        assertThat(body.get("data").toString()).contains("tokens.colors.primary").contains("UNSAFE_CONTENT");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void put_malformedJson_andNonObject_andEmpty_are400() throws Exception {
        for (String body : new String[]{"{not json", "[1,2]", "\"str\"", "{\"a\":1,\"a\":2}"}) {
            MvcResult r = mvc.perform(putJson(A, adminA, body)).andReturn();
            assertThat(r.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(json(r.getResponse().getContentAsString()).at("/data/0/path").asText()).isEqualTo("$");
        }
        assertThat(mvc.perform(putJson(A, adminA, "")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void put_unknownKeysAreRejectedByValidator_notIgnored() throws Exception {
        ObjectNode d = doc("#7c2d12");
        d.put("evil", true);
        MvcResult r = mvc.perform(putJson(A, adminA, d.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("UNKNOWN_PROPERTY");
    }

    @Test
    void put_wrongMediaType_is415() throws Exception {
        for (String ct : new String[]{"text/plain", "application/xml", "application/x-www-form-urlencoded"}) {
            MvcResult r = mvc.perform(put(URL).header("Origin", A).cookie(auth(adminA)).contentType(ct).content("<x/>")).andReturn();
            assertThat(r.getResponse().getStatus()).as(ct).isEqualTo(415);
            assertThat(json(r.getResponse().getContentAsString()).get("data").isNull()).isTrue();
        }
        assertThat(mvc.perform(put(URL).header("Origin", A).cookie(auth(adminA)).content("{}")).andReturn()
                .getResponse().getStatus()).isEqualTo(415); // no Content-Type at all
    }

    @Test
    void put_unsupportedMethodsAreNotRoutedAsWrites() throws Exception {
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(URL)
                .header("Origin", A).cookie(auth(adminA)).contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(405);
    }

    // ------------------------------------------------------------------------------------------ size limits

    @Test
    void put_json_over64Kb_is413_viaDeclaredLength_beforeAnyParsing() throws Exception {
        byte[] big = ("{\"x\":\"" + "a".repeat(64 * 1024) + "\"}").getBytes();
        MvcResult r = mvc.perform(put(URL).header("Origin", A).cookie(auth(adminA)).contentType("application/json").content(big)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
        assertThat(json(r.getResponse().getContentAsString()).get("data").isNull()).isTrue();
        // even without any credentials the filter rejects before auth (nothing is read)
        assertThat(mvc.perform(put(URL).header("Origin", A).contentType("application/json").content(big)).andReturn()
                .getResponse().getStatus()).isEqualTo(413);
    }

    @Test
    void put_json_exactlyAtLimit_isNotRejectedBySizeLayer() throws Exception {
        String pad = "{" + " ".repeat(64 * 1024 - 2) + "}";
        MvcResult r = mvc.perform(putJson(A, adminA, pad)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400); // passes the size layer; fails validation (empty object)
    }

    @Test
    void put_multipart_declaredOver12Mb_is413_withoutReadingParts() throws Exception {
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12")))
                .with(req -> {
                    ((org.springframework.mock.web.MockHttpServletRequest) req).setContent(new byte[12 * 1024 * 1024 + 1]);
                    return req;
                })).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
        assertThat(etagOf(A)).isNotNull();
    }

    @Test
    void put_multipart_perFileLimits_logo2Mb_background5Mb_hero5Mb() throws Exception {
        ObjectNode d = doc("#7c2d12");
        d.with("assets").putObject("logo").put("alt", "x");
        byte[] over2 = new byte[2 * 1024 * 1024 + 1];
        byte[] over5 = new byte[5 * 1024 * 1024 + 1];
        assertThat(mvc.perform(putMultipart(A, adminA).file(themePart(d)).file(new MockMultipartFile("logo", "l.png", "image/png", over2)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(mvc.perform(putMultipart(A, adminA).file(themePart(d)).file(new MockMultipartFile("background", "b.png", "image/png", over5)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(mvc.perform(putMultipart(A, adminA).file(themePart(d)).file(new MockMultipartFile("image.hero", "h.png", "image/png", over5)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void put_multipart_themePartOver64Kb_is413() throws Exception {
        byte[] big = ("{\"x\":\"" + "a".repeat(64 * 1024) + "\"}").getBytes();
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(new MockMultipartFile("theme", "t.json", "application/json", big))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
    }

    // ------------------------------------------------------------------------------------------ multipart part names

    @Test
    void put_multipart_unknownPart_isRejected() throws Exception {
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12")))
                .file(new MockMultipartFile("evil", "e.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode e = json(r.getResponse().getContentAsString()).at("/data/0");
        assertThat(e.get("code").asText()).isEqualTo("UNKNOWN_PROPERTY");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("evil"); // client-chosen names are not echoed
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void put_multipart_badSectionIdsAreRejectedByContractRegex() throws Exception {
        for (String name : new String[]{"image.", "image.Hero", "image.../etc", "image.a b", "image." + "a".repeat(41), "image.-x", "image.he;ro"}) {
            MvcResult r = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12")))
                    .file(new MockMultipartFile(name, "h.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
            assertThat(r.getResponse().getStatus()).as(name).isEqualTo(400);
        }
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void put_multipart_missingThemePart_is400_andDuplicatePartsAre400() throws Exception {
        MvcResult none = mvc.perform(putMultipart(A, adminA).file(new MockMultipartFile("logo", "l.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
        assertThat(none.getResponse().getContentAsString()).contains("REQUIRED");
        MvcResult dup = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12"))).file(themePart(doc("#111827")))).andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void put_multipart_heroImageForUnknownSectionOrBadFileType_is400() throws Exception {
        MvcResult unknown = mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12")))
                .file(new MockMultipartFile("image.nosuch", "h.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        ObjectNode d = doc("#7c2d12");
        d.with("assets").putObject("logo").put("alt", "x");
        MvcResult svg = mvc.perform(putMultipart(A, adminA).file(themePart(d))
                .file(new MockMultipartFile("logo", "l.svg", "image/svg+xml", "<svg/>".getBytes()))).andReturn();
        assertThat(svg.getResponse().getStatus()).isEqualTo(400);
        assertThat(svg.getResponse().getContentAsString()).contains("FILE_TYPE_NOT_ALLOWED");
    }

    // ------------------------------------------------------------------------------------------ 502, 429

    @Test
    void put_storageFailure_is502_themeUnchanged() throws Exception {
        String before = etagOf(A);
        storage.failUploads = true;
        ObjectNode d = doc("#7c2d12");
        d.with("assets").putObject("logo").put("alt", "x");
        MvcResult r = mvc.perform(putMultipart(A, adminA).file(themePart(d))
                .file(new MockMultipartFile("logo", "l.png", "image/png", FakeBrandingStorage.png(5, 5)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        assertThat(json(r.getResponse().getContentAsString()).get("data").isNull()).isTrue();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("fake store down");
        assertThat(etagOf(A)).isEqualTo(before);
    }

    @Test
    void put_rateLimit_30PerMinutePerAdmin_then429WithRetryAfter_otherAdminsAndGetUnaffected() throws Exception {
        for (int i = 0; i < 30; i++) {
            int s = mvc.perform(putJson(A, adminA, "{}")).andReturn().getResponse().getStatus();
            assertThat(s).as("request %d", i + 1).isEqualTo(400);
        }
        MvcResult limited = mvc.perform(putJson(A, adminA, doc("#7c2d12").toString())).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(json(limited.getResponse().getContentAsString()).get("data").isNull()).isTrue();
        assertThat(mvc.perform(putMultipart(A, adminA).file(themePart(doc("#7c2d12")))).andReturn().getResponse().getStatus()).isEqualTo(429);

        assertThat(mvc.perform(putJson(B, adminB, doc("#0b3d2e").toString())).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(fetch(A).getResponse().getStatus()).isEqualTo(200);
        // non-admins never consume an admin's budget and are still 403
        assertThat(mvc.perform(putJson(A, customerA, "{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}

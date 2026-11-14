package com.example.perfume_budget.security;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.isolation.AbstractTenantIsolationTest;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.TestTenant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-03: per-endpoint authorization matrix on the REAL filter chain + Postgres (acme, bravo).
 * Actors: anonymous, CUSTOMER of the tenant, ADMIN of the tenant, ADMIN token of a FOREIGN tenant (AUTH-01: ignored,
 * so anonymous), platform SUPER_ADMIN token presented on a tenant route (in both cookie names; also anonymous).
 * Expected denial: anonymous/foreign/platform = 401 (entry point), CUSTOMER = 403 (access denied handler).
 * Every denial must leave the data untouched.
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class CatalogAuthorizationMatrixIntegrationTest extends AbstractTenantIsolationTest {

    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformAdminRepository platformAdmins;

    static final String PLATFORM_PW = "Sup3r-Admin-pass!";
    static final String PLATFORM_EMAIL = "sec03-root@platform.test";

    TestTenant acme;
    TestTenant bravo;
    Cookie customer;
    Cookie admin;
    Cookie foreignAdmin;
    Cookie platformAsTenant;
    Cookie platformCookie;
    long categoryId;
    long productId;

    @BeforeEach
    void setUp() throws Exception {
        acme = tenants.acme();
        bravo = tenants.bravo();
        admin = adminCookie(acme);
        foreignAdmin = adminCookie(bravo);
        String email = "customer-sec03@acme.test";
        tenants.runAs(acme, () -> userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName("Cust").email(email).password(encoder.encode("Custpass1!a")).roles(UserRole.CUSTOMER)
                .authProvider(AuthProvider.LOCAL).isActive(true).emailVerified(true).build())));
        Map<String, Object> row = jdbc.queryForMap("SELECT id, full_name FROM tenant_acme.users WHERE email = ?", email);
        customer = new Cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken(
                email, "CUSTOMER", ((Number) row.get("id")).longValue(), (String) row.get("full_name"))));

        platformAsTenant = new Cookie("accessToken", platformToken);
        platformCookie = new Cookie("platformAccessToken", platformToken);

        jdbc.update("DELETE FROM tenant_acme.categories WHERE name LIKE 'sec03-%'");
        categoryId = createCategory("sec03-keep");
        createProductViaApi(acme, "sec03-product");
        productId = jdbc.queryForObject("SELECT id FROM tenant_acme.products WHERE name = 'sec03-product' ORDER BY id DESC LIMIT 1", Long.class);
    }

    String platformToken;

    /** Platform admin + login once per class (login is rate limited per email); removed again afterwards. */
    @org.junit.jupiter.api.BeforeAll
    void platformSetup() throws Exception {
        platformAdmins.findAll().stream().filter(a -> PLATFORM_EMAIL.equals(a.getEmail())).forEach(platformAdmins::delete);
        platformAdmins.save(PlatformAdmin.builder().email(PLATFORM_EMAIL).password(encoder.encode(PLATFORM_PW))
                .fullName("Root").isActive(true).build());
        MockHttpServletResponse login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/platform/auth/login")
                        .with(r -> { r.setRemoteAddr("10.77.3.9"); return r; })
                        .contentType("application/json")
                        .content("{\"email\":\"" + PLATFORM_EMAIL + "\",\"password\":\"" + PLATFORM_PW + "\"}"))
                .andReturn().getResponse();
        assertThat(login.getStatus()).as("platform login (fixture): " + login.getContentAsString()).isEqualTo(200);
        platformToken = login.getCookie("platformAccessToken").getValue();
    }

    @AfterAll
    void cleanUp() {
        platformAdmins.findAll().stream().filter(a -> PLATFORM_EMAIL.equals(a.getEmail())).forEach(platformAdmins::delete);
        jdbc.update("DELETE FROM tenant_acme.users WHERE email = 'customer-sec03@acme.test'");
        jdbc.update("DELETE FROM tenant_acme.categories WHERE name LIKE 'sec03-%'");
    }

    @org.junit.jupiter.api.AfterEach
    void removeFixtures() throws Exception {
        for (Long id : jdbc.queryForList("SELECT id FROM tenant_acme.products WHERE name LIKE 'sec03-%'", Long.class)) {
            mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/product/delete/" + id).header("X-Tenant-ID", "acme").cookie(admin));
        }
        jdbc.update("DELETE FROM tenant_acme.categories WHERE name LIKE 'sec03-%'");
    }

    // ---------------------------------------------------------------- helpers

    private long createCategory(String name) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/category/add-category")
                .header("X-Tenant-ID", "acme").cookie(admin).contentType("application/json")
                .content("{\"categoryName\":\"" + name + "\",\"description\":\"d\"}")).andReturn().getResponse();
        assertThat(r.getStatus()).as("fixture category").isEqualTo(200);
        return jdbc.queryForObject("SELECT id FROM tenant_acme.categories WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, name);
    }

    /** Hash of every row of the tables an endpoint under test could mutate, plus tenant registry size. */
    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String t : new String[]{"tenant_acme.categories", "tenant_acme.products", "tenant_acme.taxes", "tenant_bravo.categories",
                "tenant_bravo.products", "master.tenants"}) {
            sb.append(t).append('=').append(jdbc.queryForObject(
                    "SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, ',' ORDER BY x::text)), '') FROM " + t + " x", String.class)).append(';');
        }
        return sb.toString();
    }

    private MockHttpServletRequestBuilder req(HttpMethod m, String path, String slug) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(m, path);
        if (slug != null) {
            b.header("X-Tenant-ID", slug);
        }
        return b;
    }

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b).andReturn().getResponse().getStatus();
    }

    /** One mutating (or admin-read) request; built fresh per actor. */
    record Endpoint(String name, HttpMethod method, String path, String contentType, String body, boolean multipart) {
        static Endpoint json(String n, HttpMethod m, String p, String body) {
            return new Endpoint(n, m, p, "application/json", body, false);
        }
        static Endpoint plain(String n, HttpMethod m, String p) {
            return new Endpoint(n, m, p, null, null, false);
        }
        static Endpoint multipart(String n, HttpMethod m, String p) {
            return new Endpoint(n, m, p, null, null, true);
        }
    }

    private MockHttpServletRequestBuilder build(Endpoint e, Cookie... cookies) {
        MockHttpServletRequestBuilder b;
        if (e.multipart()) {
            b = MockMvcRequestBuilders.multipart(e.method(), e.path())
                    .param("productName", "sec03-hacked").param("currency", "GHS").param("sellingPrice", "1.00")
                    .param("costPrice", "1.00").param("isNewProduct", "true").param("isActive", "false")
                    .param("isEnlisted", "false");
        } else {
            b = MockMvcRequestBuilders.request(e.method(), e.path());
            if (e.contentType() != null) {
                b.contentType(e.contentType()).content(e.body());
            }
        }
        b.header("X-Tenant-ID", "acme");
        if (cookies.length > 0) {
            b.cookie(cookies);
        }
        return b;
    }

    private List<Endpoint> catalogMutations() {
        String disc = "{\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"startAt\":\"2030-01-01T00:00:00\",\"endAt\":\"2030-02-01T00:00:00\"}";
        return List.of(
                Endpoint.json("category add", HttpMethod.POST, "/api/v1/category/add-category", "{\"categoryName\":\"sec03-evil\",\"description\":\"x\"}"),
                Endpoint.json("category update", HttpMethod.PUT, "/api/v1/category/update/" + categoryId, "{\"categoryName\":\"sec03-renamed\",\"description\":\"x\"}"),
                Endpoint.plain("category delete", HttpMethod.DELETE, "/api/v1/category/delete/" + categoryId),
                Endpoint.multipart("product add", HttpMethod.POST, "/api/v1/product/add-product"),
                Endpoint.multipart("product update", HttpMethod.PUT, "/api/v1/product/update/" + productId),
                Endpoint.json("product discount set", HttpMethod.PUT, "/api/v1/product/admin/" + productId + "/discount", disc),
                Endpoint.plain("product discount clear", HttpMethod.DELETE, "/api/v1/product/admin/" + productId + "/discount"),
                Endpoint.plain("product delete", HttpMethod.DELETE, "/api/v1/product/delete/" + productId));
    }

    // ---------------------------------------------------------------- mutations: denial matrix

    @Test
    void catalogMutations_deniedForEveryNonAdminActor_andNothingChanges() throws Exception {
        String before = snapshot();
        for (Endpoint e : catalogMutations()) {
            assertThat(status(build(e))).as("%s anonymous", e.name()).isEqualTo(401);
            assertThat(status(build(e, customer))).as("%s CUSTOMER", e.name()).isEqualTo(403);
            assertThat(status(build(e, foreignAdmin))).as("%s foreign-tenant ADMIN token", e.name()).isEqualTo(401);
            assertThat(status(build(e, platformAsTenant))).as("%s platform token as tenant cookie", e.name()).isEqualTo(401);
            assertThat(status(build(e, platformCookie))).as("%s platform cookie", e.name()).isEqualTo(401);
            assertThat(snapshot()).as("%s must not change data on denial", e.name()).isEqualTo(before);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.categories WHERE id = ?", Integer.class, categoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE id = ?", Integer.class, productId)).isEqualTo(1);
    }

    @Test
    void anonymousDeleteWithOriginHeaderInsteadOfDevHeader_isAlsoDenied() throws Exception {
        // the original SEC-03 report: anonymous DELETE of a tenant category
        assertThat(status(req(HttpMethod.DELETE, "/api/v1/category/delete/" + categoryId, "acme"))).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.categories WHERE id = ?", Integer.class, categoryId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- mutations: allowed for the tenant ADMIN

    @Test
    void tenantAdmin_canMutateCatalog_andOnlyInOwnTenant() throws Exception {
        long extra = createCategory("sec03-extra");
        assertThat(status(build(Endpoint.json("u", HttpMethod.PUT, "/api/v1/category/update/" + extra,
                "{\"categoryName\":\"sec03-renamed\",\"description\":\"x\"}"), admin))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT name FROM tenant_acme.categories WHERE id = ?", String.class, extra))
                .isEqualTo("sec03-renamed");
        assertThat(status(build(Endpoint.plain("d", HttpMethod.DELETE, "/api/v1/category/delete/" + extra), admin))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.categories WHERE id = ?", Integer.class, extra)).isZero();

        String disc = "{\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"startAt\":\"2030-01-01T00:00:00\",\"endAt\":\"2030-02-01T00:00:00\"}";
        assertThat(status(build(Endpoint.json("ds", HttpMethod.PUT, "/api/v1/product/admin/" + productId + "/discount", disc), admin))).isEqualTo(200);
        assertThat(status(build(Endpoint.plain("dc", HttpMethod.DELETE, "/api/v1/product/admin/" + productId + "/discount"), admin))).isEqualTo(200);
        assertThat(status(build(Endpoint.plain("pd", HttpMethod.DELETE, "/api/v1/product/delete/" + productId), admin))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE id = ?", Integer.class, productId)).isZero();
    }

    // ---------------------------------------------------------------- reads

    @Test
    void publicStorefrontReads_stayOpenToAnonymous() throws Exception {
        for (String p : List.of("/api/v1/category/all", "/api/v1/product/featured", "/api/v1/product/listing",
                "/api/v1/product/search", "/api/v1/theme")) {
            assertThat(status(req(HttpMethod.GET, p, "acme"))).as(p).isEqualTo(200);
        }
        int slug = status(req(HttpMethod.GET, "/api/v1/product/no-such-slug-sec03", "acme"));
        assertThat(slug).as("public product detail by slug is not an auth failure").isNotIn(401, 403);
        // a foreign-tenant cookie is anonymous, i.e. still public data
        assertThat(status(req(HttpMethod.GET, "/api/v1/category/all", "acme").cookie(foreignAdmin))).isEqualTo(200);
    }

    @Test
    void adminReads_denyNonAdminActors_andFrontDeskRuleIsPreserved() throws Exception {
        List<String> adminOnly = List.of("/api/v1/category/" + categoryId, "/api/v1/product/admin/listing", "/api/v1/product/main/" + productId);
        for (String p : adminOnly) {
            assertThat(status(req(HttpMethod.GET, p, "acme"))).as("%s anonymous", p).isEqualTo(401);
            assertThat(status(req(HttpMethod.GET, p, "acme").cookie(customer))).as("%s customer", p).isEqualTo(403);
            assertThat(status(req(HttpMethod.GET, p, "acme").cookie(foreignAdmin))).as("%s foreign", p).isEqualTo(401);
            assertThat(status(req(HttpMethod.GET, p, "acme").cookie(platformAsTenant))).as("%s platform", p).isEqualTo(401);
            assertThat(status(req(HttpMethod.GET, p, "acme").cookie(admin))).as("%s admin", p).isEqualTo(200);
        }
        String s = "/api/v1/product/admin/search";
        assertThat(status(req(HttpMethod.GET, s, "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.GET, s, "acme").cookie(customer))).isEqualTo(403);
        assertThat(status(req(HttpMethod.GET, s, "acme").cookie(admin))).isEqualTo(200);
    }

    // ---------------------------------------------------------------- other previously-open routes

    @Test
    void devTenantTrigger_isNotReachableWhenDevEndpointsAreDisabled() throws Exception {
        String before = snapshot();
        String body = "{\"slug\":\"sec03dev\",\"displayName\":\"x\",\"ownerEmail\":\"o@x.test\",\"ownerPassword\":\"Passw0rd!x\",\"ownerFullName\":\"o\"}";
        for (Cookie[] c : new Cookie[][]{{}, {admin}, {customer}}) {
            int st = status(build(Endpoint.json("dev", HttpMethod.POST, "/api/v1/dev/tenants", body), c));
            assertThat(st).as("dev route with %d cookies", c.length).isIn(401, 403);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.tenants WHERE slug = 'sec03dev'", Integer.class)).isZero();
    }

    @Test
    void unlistedAndWrongMethodRoutesUnderFormerlyOpenPrefixes_requireAuthentication() throws Exception {
        assertThat(status(req(HttpMethod.GET, "/api/v1/auth/not-a-route", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.GET, "/api/v1/auth/login", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.POST, "/api/v1/product/featured", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.PUT, "/api/v1/category/all", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.DELETE, "/api/v1/webhook/some-token", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.GET, "/api/v1/webhook/some-token", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.DELETE, "/api/v1/theme", "acme"))).isEqualTo(401);
        assertThat(status(req(HttpMethod.GET, "/api/v1/platform/auth/login", null))).isEqualTo(401);
        assertThat(status(req(HttpMethod.DELETE, "/api/v1/platform/onboarding/signup", null))).isEqualTo(401);
        assertThat(status(req(HttpMethod.POST, "/api/v1/platform/onboarding/status", null))).isEqualTo(401);
        // intentionally public stays public: unknown webhook token is a plain 404 from the controller
        assertThat(status(req(HttpMethod.POST, "/api/v1/webhook/some-token", "acme").header("X-Paystack-Signature", "x").content("{}"))).isEqualTo(404);
    }

    @Test
    void platformRoutes_requirePlatformSuperAdmin() throws Exception {
        assertThat(status(req(HttpMethod.GET, "/api/v1/platform/tenants", null))).isEqualTo(401);
        assertThat(status(req(HttpMethod.POST, "/api/v1/platform/tenants/acme/suspend", null))).isEqualTo(401);
        assertThat(status(req(HttpMethod.POST, "/api/v1/platform/tenants/acme/suspend", null).cookie(admin))).isEqualTo(401);
        assertThat(status(req(HttpMethod.GET, "/api/v1/platform/tenants", null).cookie(platformCookie))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM master.tenants WHERE slug = 'acme'", String.class)).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- generic sweep over every controller endpoint

    /** Anonymous requests to EVERY controller endpoint that is not on the explicit public allow-list must be 401. */
    @Test
    void sweep_everyNonPublicControllerEndpointRejectsAnonymousWith401_andChangesNothing() throws Exception {
        Set<String> publicIds = new java.util.HashSet<>(AuthorizationMatrixStructureTest.PUBLIC);
        publicIds.addAll(AuthorizationMatrixStructureTest.PUBLIC_READS);
        String before = snapshot();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Class<?> c : AuthorizationMatrixStructureTest.controllers()) {
            String[] base = {""};
            RequestMapping cm = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            if (cm != null && cm.path().length > 0) {
                base[0] = cm.path()[0];
            }
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null || publicIds.contains(c.getSimpleName() + "#" + m.getName())
                        || c.getSimpleName().equals("DevTenantController")) {
                    continue;
                }
                String sub = mm.path().length > 0 ? mm.path()[0] : "";
                String path = (base[0] + sub).replaceAll("\\{[^}]+}", "1");
                for (var rm : mm.method().length > 0 ? mm.method() : new org.springframework.web.bind.annotation.RequestMethod[]{org.springframework.web.bind.annotation.RequestMethod.GET}) {
                    boolean platform = path.startsWith("/api/v1/platform/");
                    MockHttpServletRequestBuilder b = req(HttpMethod.valueOf(rm.name()), path, platform ? null : "acme");
                    int st = status(b);
                    checked++;
                    if (st != 401) {
                        failures.add(rm + " " + path + " (" + c.getSimpleName() + "#" + m.getName() + ") -> " + st);
                    }
                }
            }
        }
        assertThat(checked).as("sweep must actually cover the controllers").isGreaterThan(80);
        assertThat(failures).as("non-public endpoints reachable anonymously").isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }
}

package com.example.perfume_budget.platform;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-RD-07 AC2: {@code GET /platform/tenants?customDomainStatus=&hasCustomDomain=} and the per-state counts. Every
 * query is scoped with {@code search=<unique prefix>} so the shared registry (acme, bravo, other tests) never leaks in.
 */
@AutoConfigureMockMvc
class PlatformTenantDomainFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    static final AtomicInteger SEQ = new AtomicInteger();
    static final String PW = "Sup3r-Admin-pass!";

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired PlatformAdminRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants testTenants;
    @Autowired ObjectMapper json;

    String prefix;
    Cookie platform;

    @BeforeEach
    void seed() throws Exception {
        int n = SEQ.incrementAndGet();
        prefix = "dfx" + n + "-" + (System.nanoTime() % 100000);
        // 2 VERIFIED (one ACTIVE, one SUSPENDED), 1 PENDING (ACTIVE), 1 FAILED (ACTIVE), 2 without a domain
        tenant("a", TenantStatus.ACTIVE, CustomDomainStatus.VERIFIED);
        tenant("b", TenantStatus.SUSPENDED, CustomDomainStatus.VERIFIED);
        tenant("c", TenantStatus.ACTIVE, CustomDomainStatus.PENDING);
        tenant("d", TenantStatus.ACTIVE, CustomDomainStatus.FAILED);
        tenant("e", TenantStatus.ACTIVE, null);
        tenant("f", TenantStatus.PENDING, null);
        PlatformAdmin admin = admins.save(PlatformAdmin.builder().email("dfx" + n + "@platform.test")
                .password(encoder.encode(PW)).fullName("Root").isActive(true).build());
        MockHttpServletResponse login = mvc.perform(post("/api/v1/platform/auth/login").with(r -> {
            r.setRemoteAddr("10.66.0." + (n % 250 + 1));
            return r;
        }).contentType("application/json").content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + PW + "\"}"))
                .andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(200);
        platform = new Cookie("platformAccessToken", login.getCookie("platformAccessToken").getValue());
    }

    /** The registry is shared with other test classes: VERIFIED domains would be picked up by the re-verify job tests. */
    @AfterEach
    void removeSeededTenants() {
        tenantRepository.findAll().stream().filter(t -> t.getSlug().startsWith(prefix)).forEach(tenantRepository::delete);
    }

    private void tenant(String key, TenantStatus status, CustomDomainStatus domain) {
        String slug = prefix + key;
        Tenant t = Tenant.builder().slug(slug).displayName("Shop " + slug).schemaName("tenant_" + slug.replace('-', '_'))
                .status(status).planId(1L).ownerEmail(slug + "@owner.test").build();
        if (domain != null) {
            t.setCustomDomain(slug + ".example.com");
            t.setCustomDomainStatus(domain);
            t.setCustomDomainToken("tok-" + slug);
        }
        tenantRepository.save(t);
    }

    private JsonNode list(String query) throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/v1/platform/tenants?search=" + prefix + query).cookie(platform))
                .andReturn().getResponse();
        assertThat(r.getStatus()).as(query + " -> " + r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString()).get("data");
    }

    private int status(String query) throws Exception {
        return mvc.perform(get("/api/v1/platform/tenants?search=" + prefix + query).cookie(platform)).andReturn()
                .getResponse().getStatus();
    }

    private static List<String> keys(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("slug").asText().replaceAll("^.*-\\d*([a-f])$", "$1")));
        out.sort(String::compareTo);
        return out;
    }

    private static void assertCounts(JsonNode page, long pending, long verified, long failed, long none) {
        JsonNode c = page.get("customDomainCounts");
        assertThat(c.get("PENDING").asLong()).as("PENDING").isEqualTo(pending);
        assertThat(c.get("VERIFIED").asLong()).as("VERIFIED").isEqualTo(verified);
        assertThat(c.get("FAILED").asLong()).as("FAILED").isEqualTo(failed);
        assertThat(c.get("NONE").asLong()).as("NONE").isEqualTo(none);
        assertThat(c.size()).isEqualTo(4);
    }

    @Test
    void withoutDomainFilters_everyRowIsListed_andCountsAreAlwaysPresent() throws Exception {
        JsonNode page = list("");
        assertThat(keys(page)).containsExactly("a", "b", "c", "d", "e", "f");
        assertThat(page.get("totalElements").asInt()).isEqualTo(6);
        assertCounts(page, 1, 2, 1, 2);
    }

    @Test
    void customDomainStatus_filtersToExactlyThatState_caseInsensitive() throws Exception {
        assertThat(keys(list("&customDomainStatus=VERIFIED"))).containsExactly("a", "b");
        assertThat(keys(list("&customDomainStatus=verified"))).containsExactly("a", "b");
        assertThat(keys(list("&customDomainStatus=PENDING"))).containsExactly("c");
        assertThat(keys(list("&customDomainStatus=FAILED"))).containsExactly("d");
        assertThat(list("&customDomainStatus=VERIFIED").get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void hasCustomDomain_trueAndFalse() throws Exception {
        assertThat(keys(list("&hasCustomDomain=true"))).containsExactly("a", "b", "c", "d");
        assertThat(keys(list("&hasCustomDomain=false"))).containsExactly("e", "f");
    }

    @Test
    void filtersAndTogether_includingTheContradiction_isAnEmptyPageNotAnError() throws Exception {
        assertThat(keys(list("&customDomainStatus=VERIFIED&hasCustomDomain=true"))).containsExactly("a", "b");
        JsonNode empty = list("&customDomainStatus=VERIFIED&hasCustomDomain=false");
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalElements").asInt()).isZero();
        assertCounts(empty, 1, 2, 1, 2); // the header facet is independent of the domain filters
        assertThat(keys(list("&status=ACTIVE&customDomainStatus=VERIFIED"))).containsExactly("a");
        assertThat(keys(list("&status=SUSPENDED&hasCustomDomain=true"))).containsExactly("b");
    }

    @Test
    void counts_ignoreTheDomainFilters_butFollowStatusAndSearch() throws Exception {
        assertCounts(list("&customDomainStatus=FAILED"), 1, 2, 1, 2);
        assertCounts(list("&hasCustomDomain=false"), 1, 2, 1, 2);
        // status=ACTIVE: a(V) c(P) d(F) e(none)
        assertCounts(list("&status=ACTIVE"), 1, 1, 1, 1);
        assertCounts(list("&status=ACTIVE&customDomainStatus=PENDING"), 1, 1, 1, 1);
        // a narrower search narrows the facet: only tenant "c"
        MockHttpServletResponse r = mvc.perform(get("/api/v1/platform/tenants?search=" + prefix + "c").cookie(platform))
                .andReturn().getResponse();
        assertCounts(json.readTree(r.getContentAsString()).get("data"), 1, 0, 0, 0);
    }

    @Test
    void paging_appliesToTheFilteredSet_andCountsDoNotDependOnThePage() throws Exception {
        JsonNode p0 = list("&hasCustomDomain=true&size=3&page=0&sort=slug&direction=asc");
        JsonNode p1 = list("&hasCustomDomain=true&size=3&page=1&sort=slug&direction=asc");
        assertThat(p0.get("totalElements").asInt()).isEqualTo(4);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(keys(p0)).hasSize(3);
        assertThat(keys(p1)).containsExactly("d");
        assertCounts(p0, 1, 2, 1, 2);
        assertCounts(p1, 1, 2, 1, 2);
    }

    @Test
    void invalidValues_are400() throws Exception {
        assertThat(status("&customDomainStatus=NOPE")).isEqualTo(400);
        assertThat(status("&customDomainStatus=NONE")).isEqualTo(400); // NONE is a count key, not a stored state
        assertThat(status("&hasCustomDomain=banana")).isEqualTo(400);
    }

    @Test
    void listCarriesRegistryFieldsOnly_noInternalOrTenantSchemaData() throws Exception {
        String body = mvc.perform(get("/api/v1/platform/tenants?search=" + prefix + "&hasCustomDomain=true").cookie(platform))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("schemaName").doesNotContain("schema_name").doesNotContain("webhookToken")
                .doesNotContain("tok-" + prefix); // the DNS proof token is not part of the summary
        assertThat(body).contains("customDomainStatus");
    }

    // ---- role boundaries / isolation -------------------------------------------------------------------------

    @Test
    void anonymousAndEveryTenantRoleAreRefused_onTheFilteredList() throws Exception {
        String q = "/api/v1/platform/tenants?search=" + prefix + "&customDomainStatus=VERIFIED&hasCustomDomain=true";
        assertThat(mvc.perform(get(q)).andReturn().getResponse().getStatus()).isEqualTo(401);
        TestTenant acme = testTenants.ensureTenant("acme");
        for (String role : List.of("ADMIN", "CUSTOMER", "FRONT_DESK")) {
            String token = testTenants.runAs(acme, () -> jwtUtil.generateAccessToken(acme.adminEmail(), role, 1L, "X"));
            for (String cookieName : List.of("accessToken", "platformAccessToken")) {
                MockHttpServletRequestBuilder b = get(q).cookie(new Cookie(cookieName, token));
                MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
                assertThat(r.getStatus()).as(role + " via " + cookieName).isEqualTo(401);
                assertThat(r.getContentAsString()).doesNotContain(prefix);
            }
        }
    }

    @Test
    void aPlatformTokenDoesNotOpenATenantsOwnData_andTenantRoutesStayTenantScoped() throws Exception {
        // the platform cookie is not a tenant credential: a tenant route refuses it
        testTenants.ensureTenant("acme");
        MockHttpServletResponse r = mvc.perform(get("/api/v1/auth/me").header("X-Tenant-ID", "acme")
                .cookie(new Cookie("accessToken", platform.getValue()))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(401);
    }
}

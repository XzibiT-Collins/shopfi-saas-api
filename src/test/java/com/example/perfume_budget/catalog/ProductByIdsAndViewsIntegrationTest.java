package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.service.ProductViewRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAT-07 (GET /product/by-ids) and VIEW-01 (POST /product/{slug}/view, GET no longer counts): the public contract
 * of docs/api/catalog-public-lookup-and-views.md against the full app, two tenants.
 */
class ProductByIdsAndViewsIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired ProductViewRateLimiter limiter;

    @BeforeEach
    void resetLimiter() {
        limiter.reset();
    }

    /** A client address no other test (or earlier request of this one) used, so the shared dedupe store never collides. */
    private static String freshIp() {
        int n = NEXT_IP.getAndIncrement();
        return "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
    }

    private Cookie adminCookie(com.example.perfume_budget.support.TestTenant tenant) {
        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT id, email, full_name FROM " + tenant.schemaName() + ".users WHERE email = ?", tenant.adminEmail());
        String token = tenants.runAs(tenant, () -> jwtUtil.generateAccessToken((String) admin.get("email"), "ADMIN",
                ((Number) admin.get("id")).longValue(), (String) admin.get("full_name")));
        return new Cookie("accessToken", token);
    }

    private String slug(com.example.perfume_budget.support.TestTenant t, long id) {
        return jdbc.queryForObject("SELECT slug FROM " + t.schemaName() + ".products WHERE id = ?", String.class, id);
    }

    private long views(com.example.perfume_budget.support.TestTenant t, long id) {
        Long v = jdbc.queryForObject("SELECT view_count FROM " + t.schemaName() + ".products WHERE id = ?", Long.class, id);
        return v == null ? 0 : v;
    }

    private long listed(com.example.perfume_budget.support.TestTenant t, String name) {
        long id = product(t, name, "10.00", 5);
        jdbc.update("UPDATE " + t.schemaName() + ".products SET view_count = 0 WHERE id = ?", id);
        return id;
    }

    private MockHttpServletRequestBuilder beacon(String tenant, String slug, String ip, String ua) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post("/api/v1/product/" + slug + "/view")
                .header("X-Tenant-ID", tenant).with(r -> { r.setRemoteAddr(ip); return r; });
        return ua == null ? b : b.header("User-Agent", ua);
    }

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ CAT-07

    private MvcResult byIds(String tenant, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids" + query).header("X-Tenant-ID", tenant)).andReturn();
    }

    @Test
    void byIds_returnsPublicRowsInRequestOrder_leavesOutNonPublicMissingAndForeign() throws Exception {
        long a = listed(acme, "Ids Alpha");
        long b = listed(acme, "Ids Bravo");
        long c = listed(acme, "Ids Charlie");
        long inactive = listed(acme, "Ids Inactive");
        long unlisted = listed(acme, "Ids Unlisted");
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", inactive);
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", unlisted);
        long foreign = listed(bravo, "Ids Foreign");

        MvcResult r = byIds("acme", "?ids=" + c + "," + inactive + "," + a + "," + unlisted + ",9999999," + foreign + "," + c + "," + b);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(r).get("data");
        List<String> names = new ArrayList<>();
        data.forEach(n -> names.add(n.get("productName").asText()));
        assertThat(names).containsExactly("Ids Charlie", "Ids Alpha", "Ids Bravo");
        // exactly the public listing row: no stock quantity, no admin flags
        String raw = r.getResponse().getContentAsString();
        for (String hidden : List.of("stockQuantity", "isActive", "isEnlisted", "costPrice", "viewCount")) {
            assertThat(raw).doesNotContain(hidden);
        }
        assertThat(data.get(0).get("slug").asText()).isEqualTo(slug(acme, c));
        assertThat(data.get(0).get("price").asText()).contains("10.00");
        // and bravo sees only bravo's row for the same id list
        assertThat(body(byIds("bravo", "?ids=" + a + "," + foreign)).get("data")).hasSize(1);
        assertThat(body(byIds("bravo", "?ids=" + foreign)).get("data").get(0).get("productName").asText()).isEqualTo("Ids Foreign");
    }

    @Test
    void byIds_acceptsRepeatedParamAndHead_noMatchIsAnEmptyList() throws Exception {
        long a = listed(acme, "Ids Rep A");
        long b = listed(acme, "Ids Rep B");
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids").param("ids", String.valueOf(b))
                .param("ids", String.valueOf(a)).header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("data").get(0).get("productName").asText()).isEqualTo("Ids Rep B");
        assertThat(mvc.perform(MockMvcRequestBuilders.head("/api/v1/product/by-ids?ids=" + a).header("X-Tenant-ID", "acme"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        MvcResult none = byIds("acme", "?ids=8888888");
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(none).get("data")).isEmpty();
    }

    @Test
    void byIds_repeatedParam_entriesOfAllOccurrencesCountTogether() throws Exception {
        long a = listed(acme, "Ids Rp A");
        long b = listed(acme, "Ids Rp B");
        long c = listed(acme, "Ids Rp C");
        // order: occurrence by occurrence, entry by entry; a repeat across occurrences appears once (first position)
        MvcResult mixed = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids").param("ids", String.valueOf(b))
                .param("ids", a + "," + c).param("ids", String.valueOf(b)).header("X-Tenant-ID", "acme")).andReturn();
        List<String> names = new ArrayList<>();
        body(mixed).get("data").forEach(n -> names.add(n.get("productName").asText()));
        assertThat(names).containsExactly("Ids Rp B", "Ids Rp A", "Ids Rp C");
        // the limit of 12 counts every occurrence together, 12 is fine, 13 is not
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids").param("ids", "1,2,3,4,5,6")
                .param("ids", "7,8,9,10,11,12").header("X-Tenant-ID", "acme")).andReturn().getResponse().getStatus()).isEqualTo(200);
        MvcResult many = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids").param("ids", "1,2,3,4,5,6")
                .param("ids", "7,8,9,10,11,12").param("ids", "13").header("X-Tenant-ID", "acme")).andReturn();
        assertThat(many.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(many).get("description").asText()).isEqualTo("At most 12 ids are allowed");
        // a bad entry in a later occurrence refuses the whole request
        MvcResult bad = mvc.perform(MockMvcRequestBuilders.get("/api/v1/product/by-ids").param("ids", String.valueOf(a))
                .param("ids", "0").header("X-Tenant-ID", "acme")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(bad).get("description").asText()).isEqualTo("ids must be positive integers");
    }

    @Test
    void byIds_validation() throws Exception {
        for (String q : List.of("", "?ids=")) {
            MvcResult r = byIds("acme", q);
            assertThat(r.getResponse().getStatus()).as(q).isEqualTo(400);
            assertThat(body(r).get("description").asText()).isEqualTo("ids is required");
        }
        MvcResult many = byIds("acme", "?ids=1,2,3,4,5,6,7,8,9,10,11,12,13");
        assertThat(many.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(many).get("description").asText()).isEqualTo("At most 12 ids are allowed");
        assertThat(byIds("acme", "?ids=1,2,3,4,5,6,7,8,9,10,11,12").getResponse().getStatus()).isEqualTo(200);
        for (String bad : List.of("0", "-3", "x", "1,,2", "1.5", "99999999999999999999")) {
            MvcResult r = byIds("acme", "?ids=" + bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(r).get("description").asText()).isEqualTo("ids must be positive integers");
        }
    }

    @Test
    void byIds_countsNoView_andRecordsNoWrite() throws Exception {
        long a = listed(acme, "Ids NoView");
        for (int i = 0; i < 3; i++) {
            assertThat(byIds("acme", "?ids=" + a).getResponse().getStatus()).isEqualTo(200);
        }
        awaitBackgroundIdle();
        assertThat(views(acme, a)).isZero();
    }

    // ------------------------------------------------------------------ VIEW-01

    @Test
    void getNoLongerCountsAView() throws Exception {
        long p = listed(acme, "Get No Count");
        String s = slug(acme, p);
        for (int i = 0; i < 3; i++) {
            assertThat(status(MockMvcRequestBuilders.get("/api/v1/product/" + s).header("X-Tenant-ID", "acme")
                    .header("User-Agent", CHROME).with(r -> { r.setRemoteAddr(freshIp()); return r; }))).isEqualTo(200);
        }
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
    }

    @Test
    void beaconCountsOncePerClient_repeatIsDeduped_anotherClientCounts_204Always() throws Exception {
        long p = listed(acme, "Beacon Once");
        String s = slug(acme, p);
        String ip = freshIp();
        MvcResult first = mvc.perform(beacon("acme", s, ip, CHROME)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(204);
        assertThat(first.getResponse().getContentAsString()).isEmpty();
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(status(beacon("acme", s, ip, CHROME))).as("repeat answers the same 204").isEqualTo(204);
        assertThat(status(beacon("acme", s, ip, CHROME))).isEqualTo(204);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isEqualTo(1);
        assertThat(status(beacon("acme", s, freshIp(), CHROME))).isEqualTo(204);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isEqualTo(2);
    }

    @Test
    void ipv6AddressesOfOneSlash64AreOneVisitor() throws Exception {
        long p = listed(acme, "Beacon V6");
        String s = slug(acme, p);
        String net = "2001:db8:" + Integer.toHexString(NEXT_IP.getAndIncrement() + 4096) + ":1";
        mvc.perform(beacon("acme", s, net + "::1", CHROME)).andReturn();
        mvc.perform(beacon("acme", s, net + ":ffff:ffff:ffff:ffff", CHROME)).andReturn();
        mvc.perform(beacon("acme", s, net + ":a:b:c:d", CHROME)).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, p)).as("same /64").isEqualTo(1);
        mvc.perform(beacon("acme", s, "2001:db8:" + Integer.toHexString(NEXT_IP.getAndIncrement() + 4096) + ":1::1", CHROME)).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, p)).as("another /64").isEqualTo(2);
    }

    @Test
    void botsLibrariesPrefetchAndMissingUserAgentAreNotCounted() throws Exception {
        long p = listed(acme, "Beacon Bots");
        String s = slug(acme, p);
        for (String ua : new String[]{"Googlebot/2.1", "curl/8.5.0", "python-requests/2.31", "HeadlessChrome/126", null}) {
            assertThat(status(beacon("acme", s, freshIp(), ua))).as(String.valueOf(ua)).isEqualTo(204);
        }
        assertThat(status(beacon("acme", s, freshIp(), CHROME).header("Sec-Purpose", "prefetch"))).isEqualTo(204);
        assertThat(status(beacon("acme", s, freshIp(), CHROME).header("Purpose", "prefetch"))).isEqualTo(204);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
        // a bot did not use up the dedupe slot of its address
        String shared = freshIp();
        mvc.perform(beacon("acme", s, shared, "Googlebot/2.1")).andReturn();
        mvc.perform(beacon("acme", s, shared, CHROME)).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isEqualTo(1);
    }

    @Test
    void aMalformedSlugSpendsARateLimitToken_likeEveryOtherBeacon() throws Exception {
        long p = listed(acme, "Beacon Malformed Rate");
        String ip = freshIp();
        for (int i = 0; i < 30; i++) {
            assertThat(status(beacon("acme", i % 2 == 0 ? "Bad_Slug" : "x".repeat(300), ip, CHROME))).as("malformed beacon " + i).isEqualTo(204);
        }
        assertThat(status(beacon("acme", "Bad_Slug", ip, CHROME))).as("31st malformed one is limited").isEqualTo(429);
        assertThat(status(beacon("acme", slug(acme, p), ip, CHROME))).as("the tokens are gone for real views too").isEqualTo(429);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
    }

    @Test
    void otherMethodsOnTheBeaconPathAreRefused_neverCount_andWriteNoSiteVisit() throws Exception {
        long p = listed(acme, "Beacon Other Methods");
        String s = slug(acme, p);
        int visitsBefore = count(acme, "site_visits");
        // the route is open for POST only: an anonymous GET / PUT / DELETE / PATCH / HEAD is 401, the same answer as any
        // other protected route (the docs used to say 405)
        for (HttpMethod m : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD)) {
            int st = status(MockMvcRequestBuilders.request(m, "/api/v1/product/" + s + "/view").header("X-Tenant-ID", "acme")
                    .header("User-Agent", CHROME).with(r -> { r.setRemoteAddr(freshIp()); return r; }));
            assertThat(st).as("anonymous " + m).isEqualTo(401);
        }
        // a signed-in session reaches the router, which has no such method: 405
        for (HttpMethod m : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            int st = status(MockMvcRequestBuilders.request(m, "/api/v1/product/" + s + "/view").header("X-Tenant-ID", "acme")
                    .header("User-Agent", CHROME).cookie(adminCookie(acme)).with(r -> { r.setRemoteAddr(freshIp()); return r; }));
            assertThat(st).as("admin " + m).isEqualTo(405);
        }
        // OPTIONS with a caller chosen slug: whatever the CORS layer answers, it is not a visit
        status(MockMvcRequestBuilders.options("/api/v1/product/attacker-slug-" + System.nanoTime() + "/view").header("X-Tenant-ID", "acme")
                .with(r -> { r.setRemoteAddr(freshIp()); return r; }));
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
        assertThat(count(acme, "site_visits")).as("no method writes the address and the caller chosen slug").isEqualTo(visitsBefore);
    }

    @Test
    void headAndOtherMethodsNeverCount() throws Exception {
        long p = listed(acme, "Beacon Methods");
        String s = slug(acme, p);
        for (HttpMethod m : List.of(HttpMethod.HEAD, HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            int st = status(MockMvcRequestBuilders.request(m, "/api/v1/product/" + s + "/view").header("X-Tenant-ID", "acme")
                    .header("User-Agent", CHROME).with(r -> { r.setRemoteAddr(freshIp()); return r; }));
            assertThat(st).as(m.name()).isIn(401, 403, 404, 405);
        }
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
    }

    @Test
    void unknownUnlistedInactiveAndMalformedSlugsAnswer204AndCountNothing() throws Exception {
        long unlisted = listed(acme, "Beacon Unlisted");
        long inactive = listed(acme, "Beacon Inactive");
        jdbc.update("UPDATE tenant_acme.products SET is_enlisted = false WHERE id = ?", unlisted);
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", inactive);
        for (String s : List.of(slug(acme, unlisted), slug(acme, inactive), "no-such-product", "Bad_Slug", "x".repeat(300))) {
            assertThat(status(beacon("acme", s, freshIp(), CHROME))).as(s).isEqualTo(204);
        }
        awaitBackgroundIdle();
        assertThat(views(acme, unlisted)).isZero();
        assertThat(views(acme, inactive)).isZero();
    }

    @Test
    void anAdminSessionIsNotCounted_aCustomerSessionIs() throws Exception {
        long p = listed(acme, "Beacon Admin");
        String s = slug(acme, p);
        mvc.perform(beacon("acme", s, freshIp(), CHROME).cookie(adminCookie(acme))).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
        Customer c = customer(acme, "beacon-viewer@acme.test");
        mvc.perform(beacon("acme", s, freshIp(), CHROME).cookie(new Cookie("accessToken", c.token()))).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isEqualTo(1);
    }

    @Test
    void tenantsAreIsolated_theSameClientCountsPerTenant_andATenantCanNeverRaiseAnothersCounter() throws Exception {
        long pa = listed(acme, "Beacon Iso");
        long pb = listed(bravo, "Beacon Iso");
        String ip = freshIp();
        // acme's slug through bravo's origin: bravo has no such product, nothing is counted anywhere
        assertThat(status(beacon("bravo", slug(acme, pa), ip, CHROME))).isEqualTo(204);
        awaitBackgroundIdle();
        assertThat(views(acme, pa)).isZero();
        assertThat(views(bravo, pb)).isZero();
        // the same client address counts once in each tenant, and a repeat in either is deduped
        mvc.perform(beacon("acme", slug(acme, pa), ip, CHROME)).andReturn();
        mvc.perform(beacon("bravo", slug(bravo, pb), ip, CHROME)).andReturn();
        mvc.perform(beacon("acme", slug(acme, pa), ip, CHROME)).andReturn();
        mvc.perform(beacon("bravo", slug(bravo, pb), ip, CHROME)).andReturn();
        awaitBackgroundIdle();
        assertThat(views(acme, pa)).isEqualTo(1);
        assertThat(views(bravo, pb)).isEqualTo(1);
    }

    @Test
    void noTenantOriginIsRefused_nothingCounted() throws Exception {
        long p = listed(acme, "Beacon NoTenant");
        int st = status(MockMvcRequestBuilders.post("/api/v1/product/" + slug(acme, p) + "/view")
                .header("User-Agent", CHROME).with(r -> { r.setRemoteAddr(freshIp()); return r; }));
        assertThat(st).isBetween(400, 499);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
    }

    @Test
    void rateLimit_429WithRetryAfter_perClientAcrossTenants_andOthersAreUnaffected() throws Exception {
        long pa = listed(acme, "Beacon Rate");
        long pb = listed(bravo, "Beacon Rate");
        String ip = freshIp();
        int ok = 0;
        MvcResult limited = null;
        for (int i = 0; i < 40 && limited == null; i++) {
            // alternate tenants: the allowance is shared across them, and bots/unknown slugs spend it too
            MvcResult r = mvc.perform(i % 2 == 0 ? beacon("acme", "no-such-" + i, ip, CHROME)
                    : beacon("bravo", slug(bravo, pb), ip, "Googlebot/2.1")).andReturn();
            if (r.getResponse().getStatus() == 429) {
                limited = r;
            } else {
                assertThat(r.getResponse().getStatus()).isEqualTo(204);
                ok++;
            }
        }
        assertThat(limited).as("rate limit reached").isNotNull();
        assertThat(ok).as("burst of 30 per minute").isEqualTo(30);
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(body(limited).get("description").asText()).isEqualTo("Too many requests");
        // a real view from the limited address is refused and not counted; another address is fine
        assertThat(status(beacon("acme", slug(acme, pa), ip, CHROME))).isEqualTo(429);
        assertThat(status(beacon("acme", slug(acme, pa), freshIp(), CHROME))).isEqualTo(204);
        awaitBackgroundIdle();
        assertThat(views(acme, pa)).isEqualTo(1);
    }

    @Test
    void beaconIsNotASiteVisit_noAddressOrCallerChosenSlugReachesTheDatabase() throws Exception {
        long p = listed(acme, "Beacon NoVisit");
        int before = count(acme, "site_visits");
        mvc.perform(beacon("acme", slug(acme, p), freshIp(), CHROME)).andReturn();
        mvc.perform(beacon("acme", "attacker-chosen-" + System.nanoTime(), freshIp(), CHROME)).andReturn();
        awaitBackgroundIdle();
        assertThat(count(acme, "site_visits")).isEqualTo(before);
    }

    @Test
    void beaconIsAnonymousWithNoCsrfNeeds_andForeignOriginsAreRefusedByCors() throws Exception {
        long p = listed(acme, "Beacon Cors");
        int st = status(MockMvcRequestBuilders.post("/api/v1/product/" + slug(acme, p) + "/view")
                .header("Origin", "https://evil.example").header("User-Agent", CHROME)
                .with(r -> { r.setRemoteAddr(freshIp()); return r; }));
        assertThat(st).isEqualTo(403);
        awaitBackgroundIdle();
        assertThat(views(acme, p)).isZero();
    }
}

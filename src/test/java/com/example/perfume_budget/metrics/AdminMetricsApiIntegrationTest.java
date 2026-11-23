package com.example.perfume_budget.metrics;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API-RD-10 + API-RD-05 (customer metrics list: search, sort, last order date, deterministic order, no-order customers)
 * and API-RD-01 (per-product engagement). Real app + Postgres, tenants acme and bravo.
 */
class AdminMetricsApiIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String CUSTOMERS = "/api/v1/admin/metrics/customers";
    private static final String PRODUCT = "/api/v1/admin/metrics/products/";
    private static final String TAG = "mtq";

    private Cookie acmeAdmin;
    private Cookie bravoAdmin;

    @BeforeEach
    void login() throws Exception {
        acmeAdmin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    @AfterEach
    void cleanMetricsData() {
        for (TestTenant t : new TestTenant[]{acme, bravo}) {
            String s = t.schemaName();
            jdbc.update("DELETE FROM " + s + ".orders WHERE user_id IN (SELECT id FROM " + s + ".users WHERE email LIKE '" + TAG + "-%')");
            jdbc.update("DELETE FROM " + s + ".delivery_details WHERE user_id IN (SELECT id FROM " + s + ".users WHERE email LIKE '" + TAG + "-%')");
            jdbc.update("DELETE FROM " + s + ".users WHERE email LIKE '" + TAG + "-%'");
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private long user(TestTenant t, String name, String emailLocal, String role) {
        return jdbc.queryForObject("INSERT INTO " + t.schemaName() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES (?, ?, 'x', ?, true, true, '2026-01-15 09:00:00') RETURNING id", Long.class, name, TAG + "-" + emailLocal + "@example.com", role);
    }

    private long cust(String name, String emailLocal) {
        return user(acme, name, emailLocal, "CUSTOMER");
    }

    private void order(TestTenant t, long userId, String status, String delivery, String amount, String createdAt) {
        jdbc.update("INSERT INTO " + t.schemaName() + ".orders (order_number, user_id, status, delivery_status, total_amount, total_amount_currency, created_at) "
                + "VALUES (?, ?, ?, ?, ?::numeric, 'GHS', ?::timestamp)", "MT-" + java.util.UUID.randomUUID(), userId, status, delivery, amount, createdAt);
    }

    private MvcResult call(String url, Cookie who, TestTenant t) throws Exception {
        MockHttpServletRequestBuilder b = get(java.net.URI.create(url)).header("X-Tenant-ID", t.slug());
        if (who != null) b.cookie(who);
        return mvc.perform(b).andReturn();
    }

    private JsonNode ok(String url) throws Exception {
        MvcResult r = call(url, acmeAdmin, acme);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private void status(String url, Cookie who, TestTenant t, int expected) throws Exception {
        MvcResult r = call(url, who, t);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("fullName").asText()));
        return out;
    }

    private Cookie frontDesk() {
        String email = TAG + "-desk@example.com";
        Long id = jdbc.queryForObject("INSERT INTO " + acme.schemaName() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
        return cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken(email, "FRONT_DESK", id, "Desk")));
    }

    // ------------------------------------------------------------------------------------------ authz

    @Test
    void bothEndpointsAreAdminOnly() throws Exception {
        long p = product(acme, "Authz Perfume", "10.00", 5);
        Customer c = customer(acme, TAG + "-authz@example.com");
        Cookie desk = frontDesk();
        for (String url : new String[]{CUSTOMERS, PRODUCT + p}) {
            status(url, null, acme, 401);
            status(url, cookie("accessToken", c.token()), acme, 403);
            status(url, desk, acme, 403);
            status(url, acmeAdmin, acme, 200);
        }
    }

    // ------------------------------------------------------------------------------------------ customers list

    @Test
    void listKeepsEveryExistingFieldAndAddsLastOrderDate() throws Exception {
        long u = cust("Ama Mensah", "ama");
        order(acme, u, "COMPLETED", "DELIVERED", "40.00", "2026-03-04 10:30:00");
        order(acme, u, "COMPLETED", "PENDING", "60.00", "2026-02-01 08:00:00");

        JsonNode page = ok(CUSTOMERS + "?search=" + TAG + "-ama");
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        JsonNode row = page.get("content").get(0);
        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "fullName", "email", "orderCount", "totalSpent", "isActive", "dateJoined", "lastOrderDate");
        assertThat(row.get("id").asLong()).isEqualTo(u);
        assertThat(row.get("orderCount").asInt()).isEqualTo(2);
        assertThat(row.get("totalSpent").asText()).isEqualTo("GHS 100.00");
        assertThat(row.get("dateJoined").asText()).isEqualTo("2026-01-15");
        assertThat(row.get("lastOrderDate").asText()).isEqualTo("2026-03-04");
        // the envelope the old endpoint had
        assertThat(page.fieldNames()).toIterable().contains("content", "totalPages", "totalElements", "size", "number",
                "numberOfElements", "isFirst", "isLast", "isEmpty");
    }

    @Test
    void customersWithoutOrdersAreListedWithZeros_staffAndOtherTenantsAreNot() throws Exception {
        cust("Zero Orders", "zero");
        user(acme, "Desk Person", "deskperson", "FRONT_DESK");
        user(bravo, "Bravo Customer", "bravocust", "CUSTOMER");

        JsonNode page = ok(CUSTOMERS + "?search=" + TAG);
        assertThat(names(page)).containsExactly("Zero Orders");
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("orderCount").asInt()).isZero();
        assertThat(row.get("totalSpent").asText()).isEqualTo("GHS 0.00");
        assertThat(row.get("lastOrderDate").isNull()).isTrue();

        MvcResult bravoList = call(CUSTOMERS + "?search=" + TAG, bravoAdmin, bravo);
        assertThat(names(json.readTree(bravoList.getResponse().getContentAsString()).get("data"))).containsExactly("Bravo Customer");
    }

    @Test
    void totalSpentCountsOnlyPaidNotCancelledOrdersButOrderCountAndLastOrderCountAll() throws Exception {
        long u = cust("Kofi Paid", "kofi");
        order(acme, u, "COMPLETED", "DELIVERED", "50.00", "2026-01-20 10:00:00");
        order(acme, u, "COMPLETED", "CANCELLED", "70.00", "2026-01-21 10:00:00");
        order(acme, u, "COMPLETED", "RETURNED", "80.00", "2026-01-22 10:00:00");
        order(acme, u, "FAILED", "PENDING", "90.00", "2026-01-23 10:00:00");

        JsonNode row = ok(CUSTOMERS + "?search=" + TAG + "-kofi").get("content").get(0);
        assertThat(row.get("orderCount").asInt()).isEqualTo(4);
        assertThat(row.get("totalSpent").asText()).isEqualTo("GHS 50.00");
        assertThat(row.get("lastOrderDate").asText()).isEqualTo("2026-01-23");
    }

    @Test
    void defaultOrderIsOrdersDescAndTiesBreakByIdSoPagesNeverRepeatOrSkip() throws Exception {
        List<Long> tied = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long u = cust("Tied " + i, "tied" + i);
            tied.add(u);
            order(acme, u, "COMPLETED", "DELIVERED", "10.00", "2026-02-01 10:00:00");
        }
        long top = cust("Top Buyer", "top");
        order(acme, top, "COMPLETED", "DELIVERED", "10.00", "2026-02-01 10:00:00");
        order(acme, top, "COMPLETED", "DELIVERED", "10.00", "2026-02-02 10:00:00");

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(names(ok(CUSTOMERS + "?search=" + TAG + "&size=2&page=" + page)));
        }
        assertThat(seen).containsExactly("Top Buyer", "Tied 0", "Tied 1", "Tied 2", "Tied 3", "Tied 4");
        // ascending flips the key but keeps the id tie-breaker ascending
        assertThat(names(ok(CUSTOMERS + "?search=" + TAG + "&sortBy=orders&direction=asc")))
                .containsExactly("Tied 0", "Tied 1", "Tied 2", "Tied 3", "Tied 4", "Top Buyer");
    }

    @Test
    void sortsByTotalSpentAndByLastOrderWithNoOrderCustomersLast() throws Exception {
        long a = cust("Alpha", "alpha");
        order(acme, a, "COMPLETED", "DELIVERED", "30.00", "2026-03-01 10:00:00");
        long b = cust("Bravo", "bravoc");
        order(acme, b, "COMPLETED", "DELIVERED", "500.00", "2026-01-01 10:00:00");
        order(acme, b, "COMPLETED", "DELIVERED", "5.00", "2026-01-02 10:00:00");
        long c = cust("Charlie", "charlie");
        order(acme, c, "COMPLETED", "DELIVERED", "100.00", "2026-02-01 10:00:00");
        cust("Delta", "delta");

        String q = "?search=" + TAG;
        assertThat(names(ok(CUSTOMERS + q + "&sortBy=totalSpent&direction=desc"))).containsExactly("Bravo", "Charlie", "Alpha", "Delta");
        assertThat(names(ok(CUSTOMERS + q + "&sortBy=totalSpent&direction=asc"))).containsExactly("Delta", "Alpha", "Charlie", "Bravo");
        assertThat(names(ok(CUSTOMERS + q + "&sortBy=lastOrder&direction=desc"))).containsExactly("Alpha", "Charlie", "Bravo", "Delta");
        assertThat(names(ok(CUSTOMERS + q + "&sortBy=lastOrder&direction=asc"))).containsExactly("Bravo", "Charlie", "Alpha", "Delta");
        assertThat(names(ok(CUSTOMERS + q + "&sortBy=LAST_ORDER&direction=DESC"))).containsExactly("Alpha", "Charlie", "Bravo", "Delta");
    }

    @Test
    void searchMatchesNameOrEmailCaseInsensitivelyAndTotalCountFollowsTheFilter() throws Exception {
        cust("Esi Owusu", "esi");
        cust("Yaw Boateng", "yaw");
        assertThat(names(ok(CUSTOMERS + "?search=ESI%20OWU"))).containsExactly("Esi Owusu");
        assertThat(names(ok(CUSTOMERS + "?search=" + TAG + "-yaw@EXAMPLE"))).containsExactly("Yaw Boateng");
        JsonNode both = ok(CUSTOMERS + "?search=" + TAG + "&size=1");
        assertThat(both.get("totalElements").asInt()).isEqualTo(2);
        assertThat(both.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ok(CUSTOMERS + "?search=nobody-matches-this").get("totalElements").asInt()).isZero();
        // blank search = no filter
        assertThat(ok(CUSTOMERS + "?search=%20%20").get("totalElements").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void likeWildcardsInTheSearchAreLiteral() throws Exception {
        cust("Fifty%Off", "pct");
        cust("Fiftyxxoff", "plain");
        cust("Under_Score", "us");
        cust("UnderXScore", "ux");
        cust("Back\\Slash", "bs");
        cust("BackXSlash", "bx");

        assertThat(names(ok(CUSTOMERS + "?search=%25"))).containsExactly("Fifty%Off");           // %
        assertThat(names(ok(CUSTOMERS + "?search=y%25o"))).containsExactly("Fifty%Off");
        assertThat(names(ok(CUSTOMERS + "?search=_"))).as("underscore is literal; the e-mail local parts have none after the tag").contains("Under_Score");
        assertThat(names(ok(CUSTOMERS + "?search=r_s"))).containsExactly("Under_Score");
        assertThat(names(ok(CUSTOMERS + "?search=%5C"))).containsExactly("Back\\Slash");        // backslash
    }

    @Test
    void searchLengthSortKeyDirectionAndPageSizeAreBounded() throws Exception {
        status(CUSTOMERS + "?search=" + "a".repeat(101), acmeAdmin, acme, 400);
        status(CUSTOMERS + "?search=" + "a".repeat(100), acmeAdmin, acme, 200);
        status(CUSTOMERS + "?sortBy=email", acmeAdmin, acme, 400);
        status(CUSTOMERS + "?sortBy=1;drop%20table%20users", acmeAdmin, acme, 400);
        status(CUSTOMERS + "?direction=sideways", acmeAdmin, acme, 400);
        for (int i = 0; i < 3; i++) cust("Bulk " + i, "bulk" + i);
        assertThat(ok(CUSTOMERS + "?size=100000").get("size").asInt()).isLessThanOrEqualTo(50);
        // legacy callers that sent a Spring "sort" parameter are not broken (it is ignored, order stays deterministic)
        status(CUSTOMERS + "?sort=fullName,asc", acmeAdmin, acme, 200);
    }

    // ------------------------------------------------------------------------------------------ product engagement

    private long productWithCounters(TestTenant t, String name, Long views, Long carts, int sold) {
        long id = product(t, name, "20.00", 10);
        jdbc.update("UPDATE " + t.schemaName() + ".products SET view_count = ?, add_to_cart_count = ?, sold_count = ? WHERE id = ?", views, carts, sold, id);
        return id;
    }

    @Test
    void productMetricsMatchTheTopProductDefinitions() throws Exception {
        long p = productWithCounters(acme, "Engaged", 200L, 8L, 2);
        JsonNode d = ok(PRODUCT + p);
        assertThat(d.fieldNames()).toIterable().containsExactlyInAnyOrder("productId", "productName", "viewCount", "addToCartCount", "soldCount", "conversionRate");
        assertThat(d.get("productId").asLong()).isEqualTo(p);
        assertThat(d.get("productName").asText()).isEqualTo("Engaged");
        assertThat(d.get("viewCount").asLong()).isEqualTo(200);
        assertThat(d.get("addToCartCount").asLong()).isEqualTo(8);
        assertThat(d.get("soldCount").asLong()).isEqualTo(2);
        assertThat(d.get("conversionRate").decimalValue()).isEqualByComparingTo("25.00");

        // identical to its row in /top-products
        JsonNode top = ok("/api/v1/admin/metrics/top-products");
        JsonNode row = null;
        for (JsonNode n : top) if (n.get("productId").asLong() == p) row = n;
        assertThat(row).isNotNull();
        assertThat(row).isEqualTo(d);
    }

    @Test
    void productWithNoActivityReturnsZerosEvenWithNullCounters() throws Exception {
        long p = product(acme, "Quiet", "20.00", 3);
        jdbc.update("UPDATE " + acme.schemaName() + ".products SET view_count = NULL, add_to_cart_count = NULL WHERE id = ?", p);
        JsonNode d = ok(PRODUCT + p);
        assertThat(d.get("viewCount").asLong()).isZero();
        assertThat(d.get("addToCartCount").asLong()).isZero();
        assertThat(d.get("soldCount").asLong()).isZero();
        assertThat(d.get("conversionRate").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void productMetricsAre404ForUnknownAndForOtherTenantsProducts() throws Exception {
        long mine = productWithCounters(acme, "Mine", 5L, 1L, 1);
        long theirs = productWithCounters(bravo, "Theirs", 999L, 99L, 9);
        status(PRODUCT + 987654321L, acmeAdmin, acme, 404);
        // a bravo product id asked through acme: no such product in acme's schema (ids can collide, so use an id acme lacks)
        long missingInAcme = Math.max(mine, theirs) + 1000;
        status(PRODUCT + missingInAcme, acmeAdmin, acme, 404);
        status(PRODUCT + theirs, bravoAdmin, bravo, 200);
        // when ids collide across tenants each admin still sees only their own numbers
        if (mine == theirs) {
            assertThat(json.readTree(call(PRODUCT + mine, bravoAdmin, bravo).getResponse().getContentAsString()).get("data").get("viewCount").asLong()).isEqualTo(999);
            assertThat(ok(PRODUCT + mine).get("viewCount").asLong()).isEqualTo(5);
        }
        status(PRODUCT + "abc", acmeAdmin, acme, 400);
    }

    @Test
    void productMetricsValidateTheDateRange() throws Exception {
        long p = productWithCounters(acme, "Ranged", 10L, 4L, 1);
        String base = PRODUCT + p;
        status(base + "?from=2026-01-01&to=2026-02-01", acmeAdmin, acme, 200);
        status(base + "?from=2026-01-01", acmeAdmin, acme, 400);
        status(base + "?to=2026-01-01", acmeAdmin, acme, 400);
        status(base + "?from=2026-03-01&to=2026-02-01", acmeAdmin, acme, 400);
        status(base + "?from=nonsense&to=2026-02-01", acmeAdmin, acme, 400);
    }
}

package com.example.perfume_budget.staffinbox;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-RD-02 (notifications) and API-RD-06 (GET /auth/me for staff). Real app + Postgres, tenants acme and bravo.
 * Pins docs/api/notifications.md and docs/api/auth-me.md against the running chain.
 */
class StaffInboxAndAuthMeIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String TEAM = "/api/v1/admin/front-desk";
    private static final String INBOX = "/api/v1/admin/notifications";

    private Cookie admin;
    private long adminId;
    private long deskAId;
    private long deskBId;
    private Cookie deskA;
    private Cookie deskB;
    private Cookie shopper;
    private Map<String, Boolean> templateBefore;
    private final List<Long> notificationIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        adminId = jdbc.queryForObject("SELECT id FROM tenant_acme.users WHERE email = ?", Long.class, acme.adminEmail());
        deskAId = staff("desk-a@inbox.test");
        deskBId = staff("desk-b@inbox.test");
        deskA = cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken("desk-a@inbox.test", "FRONT_DESK", deskAId, "Desk A")));
        deskB = cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken("desk-b@inbox.test", "FRONT_DESK", deskBId, "Desk B")));
        shopper = cookie("accessToken", customer(acme, "shopper@inbox.test").token());
        templateBefore = new LinkedHashMap<>();
        jdbc.queryForList("SELECT permission, enabled FROM tenant_acme.front_desk_permission_templates")
                .forEach(r -> templateBefore.put((String) r.get("permission"), (Boolean) r.get("enabled")));
        jdbc.update("DELETE FROM tenant_acme.front_desk_permission_templates");
    }

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM tenant_acme.notification_recipients");
        jdbc.update("DELETE FROM tenant_acme.notifications");
        jdbc.update("DELETE FROM tenant_bravo.notification_recipients");
        jdbc.update("DELETE FROM tenant_bravo.notifications");
        jdbc.update("DELETE FROM tenant_acme.front_desk_user_permission_overrides");
        jdbc.update("DELETE FROM tenant_acme.front_desk_permission_templates");
        templateBefore.forEach((p, e) -> jdbc.update("INSERT INTO tenant_acme.front_desk_permission_templates (permission, enabled, created_at) VALUES (?, ?, now())", p, e));
        jdbc.update("DELETE FROM tenant_acme.delivery_details");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private long staff(String email) {
        return jdbc.queryForObject("INSERT INTO tenant_acme.users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
    }

    private MvcResult call(MockHttpServletRequestBuilder b, Cookie who, String tenant, Object content) throws Exception {
        b.header("X-Tenant-ID", tenant);
        if (who != null) b.cookie(who);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private int status(MockHttpServletRequestBuilder b, Cookie who) throws Exception {
        return call(b, who, "acme", null).getResponse().getStatus();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return body(r).get("data");
    }

    private Set<String> permissions(Cookie who) throws Exception {
        MvcResult r = call(get("/api/v1/auth/me"), who, "acme", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Set<String> out = new TreeSet<>();
        data(r).get("permissions").forEach(n -> out.add(n.asText()));
        return out;
    }

    private void setTemplate(String... perms) throws Exception {
        assertThat(call(put(TEAM + "/template"), admin, "acme", Map.of("permissions", List.of(perms))).getResponse().getStatus()).isEqualTo(200);
    }

    private void setOverrides(long userId, List<String> allowed, List<String> denied) throws Exception {
        MvcResult r = call(put(TEAM + "/users/" + userId + "/permissions"), admin, "acme",
                Map.of("allowedPermissions", allowed, "deniedPermissions", denied));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** One event with a recipient row per listed user; returns recipient ids in the order of the users. */
    private List<Long> notify(String schema, String type, String title, String refType, String refId, String createdAt, long... userIds) {
        long nid = jdbc.queryForObject("INSERT INTO " + schema + ".notifications (type, title, message, reference_type, reference_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamp) RETURNING id", Long.class, type, title, title + " message", refType, refId, createdAt);
        notificationIds.add(nid);
        List<Long> rids = new ArrayList<>();
        for (long u : userIds) {
            rids.add(jdbc.queryForObject("INSERT INTO " + schema + ".notification_recipients (notification_id, user_id, is_read, created_at) "
                    + "VALUES (?, ?, false, ?::timestamp) RETURNING id", Long.class, nid, u, createdAt));
        }
        return rids;
    }

    // ------------------------------------------------------------------------------------------ GET /auth/me

    @Test
    void me_adminAndCustomerHaveNoPermissionsButFrontDeskHasTheEffectiveSet() throws Exception {
        setTemplate("WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH");
        setOverrides(deskAId, List.of("STOCK_TRANSFER"), List.of("CUSTOMER_SEARCH"));

        MvcResult a = call(get("/api/v1/auth/me"), admin, "acme", null);
        assertThat(a.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(a).get("description").asText()).isEqualTo("Authenticated user fetched successfully");
        assertThat(data(a).get("role").asText()).isEqualTo("ADMIN");
        assertThat(data(a).get("permissions").size()).as("ADMIN permissions are [] although the template is not").isZero();
        assertThat(data(a).get("profilePicture").asText()).isEmpty();

        assertThat(permissions(shopper)).isEmpty();
        MvcResult c = call(get("/api/v1/auth/me"), shopper, "acme", null);
        assertThat(data(c).get("role").asText()).isEqualTo("CUSTOMER");

        MvcResult d = call(get("/api/v1/auth/me"), deskA, "acme", null);
        assertThat(data(d).get("role").asText()).isEqualTo("FRONT_DESK");
        assertThat(data(d).get("email").asText()).isEqualTo("desk-a@inbox.test");
        assertThat(permissions(deskA)).containsExactly("STOCK_TRANSFER", "WALK_IN_ORDER_CREATE"); // template + allowed - denied
        assertThat(permissions(deskB)).containsExactly("CUSTOMER_SEARCH", "WALK_IN_ORDER_CREATE"); // template only
        // the object is exactly the documented field list
        List<String> fields = new ArrayList<>();
        data(d).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("id", "email", "fullName", "profilePicture", "role", "permissions");
    }

    @Test
    void me_followsAdminChangesImmediatelyAndAnEmptyTemplateIsAnEmptySet() throws Exception {
        assertThat(permissions(deskA)).as("nothing seeded: a fresh tenant template is empty").isEmpty();
        setTemplate("WALK_IN_ORDER_VIEW");
        assertThat(permissions(deskA)).containsExactly("WALK_IN_ORDER_VIEW"); // same token, next call
        setOverrides(deskAId, List.of("STOCK_CONVERSION"), List.of());
        assertThat(permissions(deskA)).containsExactly("STOCK_CONVERSION", "WALK_IN_ORDER_VIEW");
        setTemplate();
        assertThat(permissions(deskA)).containsExactly("STOCK_CONVERSION");
        // the admin's own view of the same user agrees with /me
        JsonNode eff = data(call(get(TEAM + "/users/" + deskAId + "/permissions"), admin, "acme", null)).get("effectivePermissions");
        assertThat(eff.size()).isEqualTo(1);
        assertThat(eff.get(0).asText()).isEqualTo("STOCK_CONVERSION");
    }

    @Test
    void me_authAndTenantRules() throws Exception {
        assertThat(status(get("/api/v1/auth/me"), null)).isEqualTo(401);
        // an acme token is anonymous on bravo
        assertThat(call(get("/api/v1/auth/me"), deskA, "bravo", null).getResponse().getStatus()).isEqualTo(401);
        // a deactivated account's token stops working
        jdbc.update("UPDATE tenant_acme.users SET is_active = false WHERE id = ?", deskBId);
        assertThat(status(get("/api/v1/auth/me"), deskB)).isEqualTo(401);
    }

    @Test
    void permissionTableOfTheDocMatchesTheEnforcedRoutes() throws Exception {
        setTemplate("WALK_IN_ORDER_CREATE");
        setOverrides(deskBId, List.of(), List.of("WALK_IN_ORDER_CREATE")); // deskB: no permission at all
        // role-only routes work without any permission (this includes the two "nothing today" permissions' routes)
        assertThat(status(get(INBOX), deskB)).isEqualTo(200);
        assertThat(status(get("/api/v1/admin/inventory/locations"), deskB)).isEqualTo(200);
        // permission-gated routes answer 403 with the documented texts
        MvcResult search = call(post("/api/v1/admin/walk-in/customers/search"), deskA, "acme", Map.of("query", "ab"));
        assertThat(search.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(search).get("description").asText()).isEqualTo("You do not have permission to perform this walk-in action.");
        MvcResult transfers = call(get("/api/v1/admin/inventory/transfers"), deskA, "acme", null);
        assertThat(transfers.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(transfers).get("description").asText()).isEqualTo("You do not have permission to perform this action.");
        // granting STOCK_TRANSFER opens the transfer history for that user only
        setOverrides(deskAId, List.of("STOCK_TRANSFER"), List.of());
        assertThat(status(get("/api/v1/admin/inventory/transfers"), deskA)).isNotIn(401, 403);
        assertThat(status(get("/api/v1/admin/inventory/transfers"), deskB)).isEqualTo(403);
        // ADMIN-only areas stay closed to front desk whatever it holds
        for (String p : List.of("/api/v1/admin/metrics/dashboard-metric", "/api/v1/order/all", "/api/v1/tax/1", TEAM + "/users")) {
            assertThat(status(get(p), deskA)).as(p).isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------------------------------ notifications

    @Test
    void inbox_isPersonalNewestFirstAndShapedAsDocumented() throws Exception {
        List<Long> first = notify("tenant_acme", "PAYMENT_SUCCESS", "Payment received", "ORDER", "ORD-1", "2026-03-04 10:00:00", adminId, deskAId);
        List<Long> second = notify("tenant_acme", "SHOP_FLOOR_RESTOCK", "Shop floor restock needed", "PRODUCT", "42", "2026-03-05 10:00:00", adminId);
        List<Long> third = notify("tenant_acme", "PAYMENT_SUCCESS", "Payment received", "ORDER", "ORD-2", "2026-03-06 10:00:00", deskBId);

        MvcResult adminList = call(get(INBOX), admin, "acme", null);
        assertThat(adminList.getResponse().getStatus()).isEqualTo(200);
        JsonNode content = data(adminList).get("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).get("recipientId").asLong()).as("newest first").isEqualTo(second.get(0));
        assertThat(content.get(1).get("recipientId").asLong()).isEqualTo(first.get(0));
        JsonNode row = content.get(0);
        assertThat(row.get("type").asText()).isEqualTo("SHOP_FLOOR_RESTOCK");
        assertThat(row.get("referenceType").asText()).isEqualTo("PRODUCT");
        assertThat(row.get("referenceId").asText()).isEqualTo("42");
        assertThat(row.get("read").asBoolean()).isFalse();
        assertThat(row.get("readAt").isNull()).isTrue();
        assertThat(row.get("deliveredAt").isNull()).isTrue();
        assertThat(row.get("notificationId").asLong()).isNotEqualTo(row.get("recipientId").asLong());
        List<String> fields = new ArrayList<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("recipientId", "notificationId", "type", "title", "message", "referenceType",
                "referenceId", "read", "readAt", "deliveredAt", "createdAt");
        assertThat(data(adminList).get("number").asInt()).as("one-based").isEqualTo(1);

        // a front desk sees only its own rows, with its own recipient id for the shared event
        JsonNode deskRows = data(call(get(INBOX), deskA, "acme", null)).get("content");
        assertThat(deskRows.size()).isEqualTo(1);
        assertThat(deskRows.get(0).get("recipientId").asLong()).isEqualTo(first.get(1));
        assertThat(deskRows.get(0).get("notificationId").asLong()).isEqualTo(notificationIds.get(0));
        assertThat(data(call(get(INBOX), deskB, "acme", null)).get("content").get(0).get("recipientId").asLong()).isEqualTo(third.get(0));

        // paging: page 0 size 1 of the admin inbox
        MvcResult paged = call(get(INBOX + "?page=0&size=1"), admin, "acme", null);
        assertThat(data(paged).get("totalElements").asInt()).isEqualTo(2);
        assertThat(data(paged).get("totalPages").asInt()).isEqualTo(2);
        assertThat(data(paged).get("content").size()).isEqualTo(1);
    }

    @Test
    void inbox_unreadCountAndMarkRead() throws Exception {
        List<Long> ids = notify("tenant_acme", "PAYMENT_SUCCESS", "Payment received", "ORDER", "ORD-1", "2026-03-04 10:00:00", adminId, deskAId);
        notify("tenant_acme", "PAYMENT_SUCCESS", "Payment received", "ORDER", "ORD-2", "2026-03-05 10:00:00", adminId, deskAId);

        assertThat(data(call(get(INBOX + "/unread-count"), admin, "acme", null)).get("unreadCount").asLong()).isEqualTo(2);

        // a colleague's recipient id, and an unknown id, are the same 404
        MvcResult foreign = call(patch(INBOX + "/" + ids.get(0) + "/read"), deskB, "acme", null);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("description").asText()).isEqualTo("Notification not found.");
        MvcResult unknown = call(patch(INBOX + "/99999999/read"), deskA, "acme", null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknown).get("description").asText()).isEqualTo("Notification not found.");
        assertThat(call(patch(INBOX + "/abc/read"), deskA, "acme", null).getResponse().getStatus()).isEqualTo(400);
        // deskA's own row is untouched by those attempts
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notification_recipients WHERE is_read", Integer.class)).isZero();

        // admin marks own row: returns the row, read true, readAt set; repeating keeps readAt
        MvcResult one = call(patch(INBOX + "/" + ids.get(0) + "/read"), admin, "acme", null);
        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(one).get("read").asBoolean()).isTrue();
        String readAt = data(one).get("readAt").asText();
        assertThat(readAt).isNotBlank();
        MvcResult again = call(patch(INBOX + "/" + ids.get(0) + "/read"), admin, "acme", null);
        assertThat(data(again).get("readAt").asText().substring(0, 19)).isEqualTo(readAt.substring(0, 19));
        assertThat(data(call(get(INBOX), admin, "acme", null)).get("content").get(1).get("readAt").asText().substring(0, 19)).isEqualTo(readAt.substring(0, 19));
        assertThat(data(call(get(INBOX + "/unread-count"), admin, "acme", null)).get("unreadCount").asLong()).isEqualTo(1);

        // read-all: 204 empty body, only the caller's rows; also fine when nothing is unread
        MvcResult all = call(patch(INBOX + "/read-all"), admin, "acme", null);
        assertThat(all.getResponse().getStatus()).isEqualTo(204);
        assertThat(all.getResponse().getContentAsString()).isEmpty();
        assertThat(data(call(get(INBOX + "/unread-count"), admin, "acme", null)).get("unreadCount").asLong()).isZero();
        assertThat(data(call(get(INBOX + "/unread-count"), deskA, "acme", null)).get("unreadCount").asLong()).as("colleague untouched").isEqualTo(2);
        assertThat(call(patch(INBOX + "/read-all"), admin, "acme", null).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void inbox_rolesAndTenants() throws Exception {
        List<Long> acmeRows = notify("tenant_acme", "PAYMENT_SUCCESS", "Acme only", "ORDER", "ORD-A", "2026-03-04 10:00:00", adminId);
        long bravoAdmin = jdbc.queryForObject("SELECT id FROM tenant_bravo.users WHERE email = ?", Long.class, bravo.adminEmail());
        notify("tenant_bravo", "PAYMENT_SUCCESS", "Bravo only", "ORDER", "ORD-B", "2026-03-04 10:00:00", bravoAdmin);

        for (MockHttpServletRequestBuilder b : List.of(get(INBOX), get(INBOX + "/unread-count"),
                patch(INBOX + "/" + acmeRows.get(0) + "/read"), patch(INBOX + "/read-all"))) {
            assertThat(status(b, null)).as("anonymous " + b).isEqualTo(401);
        }
        for (MockHttpServletRequestBuilder b : List.of(get(INBOX), get(INBOX + "/unread-count"),
                patch(INBOX + "/" + acmeRows.get(0) + "/read"), patch(INBOX + "/read-all"))) {
            assertThat(status(b, shopper)).as("customer").isEqualTo(403);
        }
        // front desk is allowed with no permission at all (role-only), and an acme token is anonymous on bravo
        assertThat(status(get(INBOX), deskB)).isEqualTo(200);
        assertThat(call(get(INBOX), admin, "bravo", null).getResponse().getStatus()).isEqualTo(401);
        // each tenant lists only its own rows
        MvcResult bravoLogin = loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD);
        Cookie bravoCookie = cookie("accessToken", setCookieValue(bravoLogin, "accessToken"));
        JsonNode bravoRows = data(call(get(INBOX), bravoCookie, "bravo", null)).get("content");
        assertThat(bravoRows.size()).isEqualTo(1);
        assertThat(bravoRows.get(0).get("title").asText()).isEqualTo("Bravo only");
        assertThat(data(call(get(INBOX), admin, "acme", null)).get("content").get(0).get("title").asText()).isEqualTo("Acme only");
    }
}

package com.example.perfume_budget.checkout;

import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * EVT-01, end to end on the real stack (Postgres, JPA transactions, the real listeners and mail executor; only the
 * mail transport is a mock that records what was handed to it).
 *
 * <ul>
 *   <li>(a) the admin "new order" e-mail of a checkout whose transaction fails AT COMMIT (after the order event was
 *       published) is never sent; the next, committed checkout's e-mail is sent exactly once, rendered from the
 *       order re-read after the commit.</li>
 *   <li>(a) an admin cancel whose first attempt publishes its status e-mail event and then rolls back at commit
 *       (its deferred coupon UPDATE is the deadlock victim) is retried and the customer gets exactly one e-mail.</li>
 *   <li>(c) API-04 review probe Q6: no spelling of the webhook URL, overlong UTF-8 included, stores the token.</li>
 * </ul>
 */
class SideEffectsAfterCommitIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Cookie adminA;

    @BeforeEach
    void admin() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    @AfterEach
    void dropFaults() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_evt01_fault ON tenant_acme.orders");
        jdbc.execute("DROP TRIGGER IF EXISTS it_evt01_fault ON tenant_acme.coupons");
        jdbc.execute("DROP FUNCTION IF EXISTS tenant_acme.it_evt01_fault()");
        jdbc.execute("DROP SEQUENCE IF EXISTS tenant_acme.it_evt01_raised");
    }

    /** The first {@code failures} matching UPDATEs on {@code table} fail with a real 40P01; counts what it raised. */
    private void injectDeadlocks(String table, String condition, int failures) {
        dropFaults();
        jdbc.execute("CREATE SEQUENCE tenant_acme.it_evt01_raised");
        jdbc.execute("CREATE FUNCTION tenant_acme.it_evt01_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF " + condition + " AND (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM tenant_acme.it_evt01_raised) < "
                + failures + " THEN PERFORM nextval('tenant_acme.it_evt01_raised'); "
                + "RAISE EXCEPTION 'injected deadlock' USING ERRCODE = '40P01'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER it_evt01_fault BEFORE UPDATE ON tenant_acme." + table + " FOR EACH ROW "
                + "EXECUTE FUNCTION tenant_acme.it_evt01_fault()");
    }

    private long raised() {
        return jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM tenant_acme.it_evt01_raised", Long.class);
    }

    private List<MailMessage> mails(String subjectPart) {
        return Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject() != null && m.subject().contains(subjectPart))
                .toList();
    }

    // ---- (a) admin "new order" e-mail

    @Test
    void newOrderMailOfACheckoutRolledBackAtCommitIsNeverSent_theCommittedOneOnce() throws Exception {
        Customer c = customer(acme, "evt01-a@acme.test");
        cart(c, product(acme, "P-EVT01-A", "100.00", 20), 1);
        // the checkout's last write (order -> INITIATED, flushed at commit, after the order event was published) fails
        injectDeadlocks("orders", "NEW.status = 'INITIATED'", 1);
        MvcResult failed = checkout(c, null, null);
        assertThat(failed.getResponse().getStatus()).as(failed.getResponse().getContentAsString()).isEqualTo(503);
        assertThat(raised()).as("the fault really hit the checkout's commit").isEqualTo(1);
        assertThat(count(acme, "orders")).as("the checkout rolled back").isZero();
        await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(mails("New Order Received")).isEmpty());

        MvcResult ok = checkout(c, null, null);
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        String orderNumber = body(ok).at("/data/orderNumber").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mails("New Order Received - " + orderNumber)).hasSize(1));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(mails("New Order Received")).hasSize(1));
        assertThat(mails("New Order Received").getFirst().html()).as("rendered from the committed order").isNotBlank();
    }

    // ---- (a) a retried admin transaction e-mails the customer once

    @Test
    void adminCancelRetriedAfterACommitTimeDeadlockEmailsTheCustomerOnce() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couponCode", "EVT01R");
        m.put("discountType", "FLAT");
        m.put("discountValue", "5");
        m.put("minimumCartAmountForDiscount", "0");
        m.put("usageLimit", 10);
        m.put("isActive", true);
        m.put("startDate", LocalDate.now().minusDays(1).toString());
        m.put("expirationDate", LocalDate.now().plusDays(30).toString());
        MvcResult cr = mvc.perform(post("/api/v1/coupon/add-coupon").header("X-Tenant-ID", acme.slug()).cookie(adminA)
                .contentType("application/json").content(json.writeValueAsString(m))).andReturn();
        assertThat(cr.getResponse().getStatus()).as(cr.getResponse().getContentAsString()).isEqualTo(200);
        Customer c = customer(acme, "evt01-r@acme.test");
        cart(c, product(acme, "P-EVT01-R", "100.00", 20), 1);
        MvcResult placed = checkout(c, null, "couponCode=EVT01R");
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        String orderNumber = body(placed).at("/data/orderNumber").asText();

        // the cancel gives the coupon back with a deferred UPDATE at commit, i.e. AFTER the status event is published
        injectDeadlocks("coupons", "TRUE", 1);
        MvcResult r = mvc.perform(put("/api/v1/order/update-order-status/" + orderNumber).header("X-Tenant-ID", acme.slug())
                .cookie(adminA).contentType("application/json")
                .content(json.writeValueAsString(Map.of("orderStatus", "CANCELLED")))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(raised()).as("attempt 1 published, then was the deadlock victim at commit").isEqualTo(1);
        assertThat(orderStatus(acme, orderNumber)).isEqualTo("CANCELLED");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mails("Order Status Update")).filteredOn(x -> x.to().equals("evt01-r@acme.test")).hasSize(1));
        await().during(Duration.ofMillis(1000)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(mails("Order Status Update")).filteredOn(x -> x.to().equals("evt01-r@acme.test")).hasSize(1));
    }

    // ---- (c) probe Q6

    @Test
    void q6_noSpellingOfTheWebhookUrlStoresTheToken() throws Exception {
        Gateway g = configureGateway(acme);
        String ev = event("charge.failed", "Tnonexistent", 100);
        String[] paths = {"/api/v1/%77ebhook/", "/api/v1/%57ebhook/", "/api/v1/webhook%2F", "/api/v1/%2577ebhook/",
                "/api/%76%31/webhook/", "/api/v1/webhook/./", "/api/v1/x/../webhook/", "/api/v1//webhook/",
                "/API/V1/WEBHOOK/", "/api/v1/webhook;a=b/", "/api/v1/webhook/%2E/", "/api/v1/%25252577ebhook/",
                "/api/v1/%2525252577ebhook/", "/api/v1/webhoo%6B/", "/api/v1/webhook\\", "/api/v1/we%C1%A2hook/",
                "/api/v1/%C1%B7ebhook/", "/api/v1/we%25C1%25A2hook/", "/api/v1/we%E0%81%A2hook/", "/api/v1/we%FFhook/",
                "/api/v1/we%ED%A0%80hook/",
                // rework 1 (F2): valid UTF-8 look-alikes (review probe: zero-width space, full-width w) and more
                "/api/v1/w%E2%80%8Bebhook/", "/api/v1/%EF%BD%97ebhook/", "/api/v1/web%C2%ADhook/",
                "/api/v1/w%D0%B5bhook/", "/api/v1/w%C3%A9bhook/", "/api/v1/web-hook/", "/api/v1/webhok/",
                "/api/v1/hook/", "/"};
        for (String pth : paths) {
            try {
                mvc.perform(post(URI.create(pth + g.token())).contentType("application/json")
                        .header("X-Tenant-ID", acme.slug())
                        .header("X-Paystack-Signature", hmac512(ev, g.secret())).content(ev)).andReturn();
            } catch (Exception rejectedBeforeTheFilter) {
                // a spelling the container / firewall refuses outright never reaches SiteVisitFilter either
            }
        }
        int rows = 0;
        for (String s : jdbc.queryForList("SELECT table_schema FROM information_schema.tables WHERE table_name = 'site_visits'", String.class)) {
            rows += jdbc.queryForObject("SELECT count(*) FROM " + s + ".site_visits WHERE page LIKE ?", Integer.class, "%" + g.token() + "%");
        }
        assertThat(rows).isZero();
    }

    /** Rework 1 (F2): the webhook guard and the token redaction do not cost legitimate visits. */
    @Test
    void legitimateVisitsWithEncodedCharactersAreStillRecordedAsIs() throws Exception {
        String[] pages = {"/api/v1/product/caf%C3%A9-noir", "/api/v1/product/a%20b", "/api/v1/product/a+b",
                "/api/v1/product/%E2%82%AC-deal", "/api/v1/product/%F0%9F%98%80"};
        for (String page : pages) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(page))
                    .header("X-Tenant-ID", acme.slug())).andReturn();
        }
        for (String page : pages) {
            int rows = 0;
            for (String s : jdbc.queryForList("SELECT table_schema FROM information_schema.tables WHERE table_name = 'site_visits'", String.class)) {
                rows += jdbc.queryForObject("SELECT count(*) FROM " + s + ".site_visits WHERE page = ?", Integer.class, page);
            }
            assertThat(rows).as(page).isEqualTo(1);
        }
    }
}

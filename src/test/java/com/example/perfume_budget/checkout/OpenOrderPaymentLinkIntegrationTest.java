package com.example.perfume_budget.checkout;

import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * PAY-03 rework. The owner's order detail ({@code GET /api/v1/order/{orderNumber}}) carries the Paystack
 * {@code authorizationUrl} while (and only while) the order is open: payment INITIATED and now before
 * {@code expiresAt}. Null when paid, failed, cancelled, expired (even before the expiry job ran), for the ADMIN view, and
 * never in list rows. Other customers / tenants get the usual 404. Also: an open same-cart order whose payment has no
 * authorization URL is a 409 that does not claim a different cart; a changed default address is a different cart; an
 * unavailable product is 400 {@code PRODUCT_UNAVAILABLE}. Real Postgres, tenants acme + bravo, Paystack stubbed.
 */
class OpenOrderPaymentLinkIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Customer ready(String email, long productId, int qty) {
        Customer c = customer(acme, email);
        cart(c, productId, qty);
        return c;
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private MvcResult detail(Customer c, String orderNumber) throws Exception {
        return mvc.perform(as(get("/api/v1/order/" + orderNumber), c)).andReturn();
    }

    private Cookie admin() throws Exception {
        return cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    private void ageOrder(String orderNumber, int minutes) {
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - make_interval(mins => ?) WHERE order_number = ?",
                minutes, orderNumber);
    }

    // ---- owner sees the URL while the order is open ----

    @Test
    void ownerSeesTheAuthorizationUrlWhileTheOrderIsOpenAndNowhereElse() throws Exception {
        long p = product(acme, "Link One", "50.00", 10);
        Customer c = ready("l1@acme.test", p, 1);
        JsonNode co = ok(checkout(c, "link-key-0001", null));
        String n = co.get("orderNumber").asText();
        String url = co.at("/data/authorization_url").asText();
        assertThat(url).startsWith("https://checkout.paystack.com/");

        JsonNode d = ok(detail(c, n));
        assertThat(d.get("authorizationUrl").asText()).isEqualTo(url);
        assertThat(d.get("reference").asText()).isEqualTo(co.at("/data/reference").asText());
        assertThat(d.get("paymentStatus").asText()).isEqualTo("INITIATED");
        assertThat(d.has("accessCode")).as("the access code is not exposed").isFalse();
        assertThat(d.has("access_code")).isFalse();
        // the order number in any case works the same (the detail route upper-cases it)
        assertThat(ok(detail(c, n.toLowerCase())).get("authorizationUrl").asText()).isEqualTo(url);

        // still inside the TTL (59 min of 60): open
        ageOrder(n, 59);
        assertThat(ok(detail(c, n)).get("authorizationUrl").asText()).isEqualTo(url);

        // list rows never carry it
        JsonNode mine = ok(mvc.perform(as(get("/api/v1/order/my-orders"), c)).andReturn());
        assertThat(mine.toString()).doesNotContain("authorizationUrl").doesNotContain(url);

        // the ADMIN is not the owner: detail has the key, always null; the admin list does not have it
        Cookie a = admin();
        JsonNode ad = ok(mvc.perform(get("/api/v1/order/" + n).header("X-Tenant-ID", "acme").cookie(a)).andReturn());
        assertThat(ad.get("orderNumber").asText()).isEqualTo(n);
        assertThat(ad.has("authorizationUrl")).isTrue();
        assertThat(ad.get("authorizationUrl").isNull()).isTrue();
        JsonNode all = ok(mvc.perform(get("/api/v1/order/all").header("X-Tenant-ID", "acme").cookie(a)).andReturn());
        assertThat(all.toString()).contains(n).doesNotContain("authorizationUrl").doesNotContain(url);
    }

    // ---- null once the order is no longer open ----

    @Test
    void urlIsNullOnceTheOrderExpiredEvenBeforeTheExpiryJobRan() throws Exception {
        long p = product(acme, "Link Expired", "50.00", 10);
        Customer c = ready("l2@acme.test", p, 1);
        String n = ok(checkout(c, "link-key-0002", null)).get("orderNumber").asText();
        ageOrder(n, 61);

        JsonNode d = ok(detail(c, n));
        assertThat(orderStatus(acme, n)).as("not cancelled yet").isEqualTo("INITIATED");
        assertThat(d.get("paymentStatus").asText()).isEqualTo("INITIATED");
        assertThat(d.get("authorizationUrl").isNull()).isTrue();
    }

    @Test
    void urlIsNullAfterCancel() throws Exception {
        long p = product(acme, "Link Cancel", "50.00", 10);
        Customer c = ready("l3@acme.test", p, 1);
        String n = ok(checkout(c, "link-key-0003", null)).get("orderNumber").asText();
        JsonNode cancelled = ok(cancel(c, n));
        assertThat(cancelled.get("authorizationUrl").isNull()).isTrue();

        JsonNode d = ok(detail(c, n));
        assertThat(d.get("paymentStatus").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("authorizationUrl").isNull()).isTrue();
    }

    @Test
    void urlIsNullOncePaidOrFailed() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "Link Paid", "50.00", 10);
        Customer paid = ready("l4@acme.test", p, 1);
        JsonNode co = ok(checkout(paid, "link-key-0004", null));
        String n = co.get("orderNumber").asText();
        webhook(g, event("charge.success", co.at("/data/reference").asText(), totalMinor(n)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, n)).isEqualTo("COMPLETED"));
        awaitBackgroundIdle();
        JsonNode d = ok(detail(paid, n));
        assertThat(d.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(d.get("authorizationUrl").isNull()).isTrue();

        Customer failed = ready("l5@acme.test", p, 1);
        JsonNode cf = ok(checkout(failed, "link-key-0005", null));
        String nf = cf.get("orderNumber").asText();
        webhook(g, event("charge.failed", cf.at("/data/reference").asText(), totalMinor(nf)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, nf)).isEqualTo("FAILED"));
        JsonNode df = ok(detail(failed, nf));
        assertThat(df.get("authorizationUrl").isNull()).isTrue();
    }

    // ---- never to another customer or another tenant ----

    @Test
    void anotherCustomerOrAnotherTenantNeverGetsTheUrl() throws Exception {
        long p = product(acme, "Link Iso", "50.00", 10);
        Customer owner = ready("l6@acme.test", p, 1);
        Customer other = customer(acme, "l7@acme.test");
        Customer foreign = customer(bravo, "l8@bravo.test");
        JsonNode co = ok(checkout(owner, "link-key-0006", null));
        String n = co.get("orderNumber").asText();
        String url = co.at("/data/authorization_url").asText();

        for (Customer x : new Customer[]{other, foreign}) {
            MvcResult r = detail(x, n);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(404);
            assertThat(body(r).at("/data/code").asText()).isEqualTo("ORDER_NOT_FOUND");
            assertThat(r.getResponse().getContentAsString()).doesNotContain(url).doesNotContain("authorizationUrl");
        }
        // the owner token presented to the other tenant is refused too, and nothing leaks
        MvcResult cross = mvc.perform(get("/api/v1/order/" + n).header("X-Tenant-ID", "bravo")
                .cookie(cookie("accessToken", owner.token()))).andReturn();
        assertThat(cross.getResponse().getStatus()).isIn(401, 403, 404);
        assertThat(cross.getResponse().getContentAsString()).doesNotContain(url);
    }

    // ---- LOW: an open same-cart order that cannot be resumed ----

    @Test
    void sameCartWithAnOpenOrderThatHasNoAuthorizationUrlIs409WithoutClaimingADifferentCart() throws Exception {
        long p = product(acme, "Link Legacy", "50.00", 10);
        long q = product(acme, "Link Legacy Two", "10.00", 5);
        Customer c = ready("l9@acme.test", p, 1);
        String n = ok(checkout(c, "link-key-0009", null)).get("orderNumber").asText();
        // legacy / corrupt row: INITIATED but the payment never stored its Paystack URL
        jdbc.update("UPDATE tenant_acme.payments SET authorization_url = NULL WHERE order_id = "
                + "(SELECT id FROM tenant_acme.orders WHERE order_number = ?)", n);

        MvcResult same = checkout(c, "link-key-0010", null);
        assertThat(same.getResponse().getStatus()).as(same.getResponse().getContentAsString()).isEqualTo(409);
        JsonNode b = body(same);
        assertThat(b.at("/data/code").asText()).isEqualTo("OPEN_ORDER_EXISTS");
        assertThat(b.at("/data/orderNumber").asText()).isEqualTo(n);
        assertThat(b.at("/data/expiresAt").asText()).endsWith("Z");
        assertThat(b.get("description").asText()).doesNotContain("different cart").contains("this cart");
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(count(acme, "orders")).isEqualTo(1);
        // the order page has nothing to resume either: the only way on is cancel
        assertThat(ok(detail(c, n)).get("authorizationUrl").isNull()).isTrue();

        // a genuinely different cart keeps the "different cart" wording
        cart(c, q, 1);
        MvcResult diff = checkout(c, "link-key-0011", null);
        assertThat(diff.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(diff).get("description").asText()).contains("different cart");

        // cancel, then the same key succeeds
        assertThat(cancel(c, n).getResponse().getStatus()).isEqualTo(200);
        assertThat(ok(checkout(c, "link-key-0010", null)).get("idempotentReplay").asBoolean()).isFalse();
    }

    // ---- documented frontend cases ----

    @Test
    void changingTheDefaultAddressIsADifferentCart() throws Exception {
        long p = product(acme, "Link Address", "50.00", 10);
        Customer c = ready("l10@acme.test", p, 1);
        String n = ok(checkout(c, "link-key-0012", null)).get("orderNumber").asText();
        long other = extraAddress(c);
        jdbc.update("UPDATE tenant_acme.delivery_details SET is_default = (id = ?) WHERE user_id = ?", other, c.id());

        MvcResult r = checkout(c, "link-key-0013", null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("OPEN_ORDER_EXISTS");
        assertThat(body(r).at("/data/orderNumber").asText()).isEqualTo(n);
    }

    @Test
    void aDeactivatedProductIs400ProductUnavailableAndTheOpenOrderCanBeCancelled() throws Exception {
        long p = product(acme, "Link Gone", "50.00", 10);
        Customer c = ready("l11@acme.test", p, 1);
        String n = ok(checkout(c, "link-key-0014", null)).get("orderNumber").asText();
        jdbc.update("UPDATE tenant_acme.products SET is_active = false WHERE id = ?", p);

        MvcResult r = checkout(c, "link-key-0015", null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("PRODUCT_UNAVAILABLE");
        assertThat(body(r).get("description").asText())
                .isEqualTo("One or more products in your cart are no longer available for ecommerce purchase.");
        assertThat(initiateCalls.get()).isEqualTo(1);

        assertThat(ok(cancel(c, n)).get("paymentStatus").asText()).isEqualTo("CANCELLED");
    }
}

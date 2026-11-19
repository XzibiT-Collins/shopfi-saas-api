package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * PAY-03b (PAY-03 re-review probes P1, P2, P3, P7): the owner's order detail shows the pay link exactly while the order
 * is open, never lets a cache keep it, never logs it, and an open order keeps holding the coupon's last slot for the
 * second tab's replay.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderDetailPayLinkIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Customer ready(String email, long productId, int qty) {
        Customer c = customer(acme, email);
        cart(c, productId, qty);
        return c;
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private MvcResult detail(Customer c, String n) throws Exception {
        return mvc.perform(as(get("/api/v1/order/" + n), c)).andReturn();
    }

    private void ageSeconds(String n, int seconds) {
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - make_interval(secs => ?) WHERE order_number = ?", seconds, n);
    }

    /** P1: 60 min minus 20 s is open (link shown), 60 min plus 20 s is closed (null). Kills a TTL off by a minute (Y04/Y05). */
    @Test
    void theDetailShowsThePayLinkUntilTheOneHourTtl_toTheSecond() throws Exception {
        long p = product(acme, "Bound", "50.00", 10);
        Customer c = ready("pl1@acme.test", p, 1);
        JsonNode co = ok(checkout(c, "pl-key-0001", null));
        String n = co.get("orderNumber").asText();
        String url = co.at("/data/authorization_url").asText();
        ageSeconds(n, 3600 - 20);
        assertThat(ok(detail(c, n)).get("authorizationUrl").asText()).isEqualTo(url);
        ageSeconds(n, 3600 + 20);
        assertThat(ok(detail(c, n)).get("authorizationUrl").isNull()).isTrue();
    }

    /** P2: a shared cache / CDN must never store a response that carries a pay link (kills Y11). */
    @Test
    void theOwnerDetailIsNotCacheable() throws Exception {
        long p = product(acme, "Cache", "50.00", 10);
        Customer c = ready("pl2@acme.test", p, 1);
        String n = ok(checkout(c, "pl-key-0002", null)).get("orderNumber").asText();
        MvcResult r = detail(c, n);
        assertThat(ok(r).get("authorizationUrl").isNull()).isFalse();
        String cc = String.valueOf(r.getResponse().getHeader("Cache-Control"));
        assertThat(cc).contains("no-store").doesNotContain("public");
        assertThat(r.getResponse().getHeaders("Cache-Control").toString()).doesNotContain("max-age=3");
    }

    /** P3: the pay URL and its access code are never written to the application log (kills Y12). */
    @Test
    void theUrlAndItsAccessCodeAreNeverLogged(CapturedOutput out) throws Exception {
        long p = product(acme, "Logs", "50.00", 10);
        Customer c = ready("pl3@acme.test", p, 1);
        JsonNode co = ok(checkout(c, "pl-key-0003", null));
        String n = co.get("orderNumber").asText();
        String url = co.at("/data/authorization_url").asText();
        ok(checkout(c, "pl-key-0004", null)); // cross-key reuse
        ok(detail(c, n));
        ok(cancel(c, n));
        assertThat(out.getAll()).as("capture works").contains(n);
        assertThat(out.getAll()).doesNotContain(url);
        assertThat(out.getAll()).doesNotContain(url.substring(url.lastIndexOf('/') + 1));
    }

    /** P7: the open order holds the coupon's last slot; a second tab must get the replay, not a coupon refusal (kills X06). */
    @Test
    void aSecondTabReusesTheOpenOrderWhenItHoldsTheLastCouponSlot() throws Exception {
        long p = product(acme, "LastSlot", "50.00", 10);
        coupon(acme, "LAST1", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET usage_limit = 1, per_user_usage_limit = 1 WHERE code = 'LAST1'");
        Customer c = ready("pl7@acme.test", p, 1);
        JsonNode first = ok(checkout(c, "pl-key-0071", "couponCode=LAST1"));
        JsonNode s = ok(checkout(c, "pl-key-0072", "couponCode=last1"));
        assertThat(s.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(s.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(initiateCalls.get()).isEqualTo(1);
    }
}

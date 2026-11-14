package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/** PAY-01b review follow-ups (QA Q1-Q5, Security S2/S3). */
class CheckoutReworkIntegrationTest extends AbstractCheckoutIntegrationTest {

    private record Placed(Customer c, long productId, String orderNumber, String reference) {}

    private Placed place(String email, int qty, String key) throws Exception {
        long p = product(acme, "Rework " + email, "10.00", 10);
        Customer c = customer(acme, email);
        cart(c, p, qty);
        JsonNode d = body(checkout(c, key, null)).get("data");
        return new Placed(c, p, d.get("orderNumber").asText(), d.at("/data/reference").asText());
    }

    private String adminToken() throws Exception {
        return setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
    }

    @Test
    void sameTenantOtherCustomerCannotReadAnOrder_identical404() throws Exception {
        Placed a = place("r1@acme.test", 1, null);
        Customer b = customer(acme, "r1b@acme.test");
        MvcResult cross = mvc.perform(as(get("/api/v1/order/" + a.orderNumber()), b)).andReturn();
        MvcResult unknown = mvc.perform(as(get("/api/v1/order/ORD-DOESNOTEXIST"), b)).andReturn();
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
        assertThat(cross.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(body(cross).at("/data/code").asText()).isEqualTo("ORDER_NOT_FOUND");
        // the owner still reads it
        assertThat(mvc.perform(as(get("/api/v1/order/" + a.orderNumber()), a.c())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void corsPreflightAllowsIdempotencyKeyHeader() throws Exception {
        MvcResult r = mvc.perform(options("/api/v1/cart/checkout")
                .header("Origin", "https://" + ACME_DOMAIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Idempotency-Key,Content-Type")
                .header("X-Tenant-ID", "acme")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("Idempotency-Key");
    }

    @Test
    void adminOrderListSortIsWhitelistedAndShowsRefundRequired() throws Exception {
        Placed o = place("r2@acme.test", 1, null);
        String admin = adminToken();
        for (String bad : new String[]{"user.password,asc", "nonsense,desc"}) {
            MvcResult r = mvc.perform(get("/api/v1/order/all").param("sort", bad).header("X-Tenant-ID", "acme")
                    .cookie(cookie("accessToken", admin))).andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(r).at("/data/code").asText()).isEqualTo("INVALID_SORT");
        }
        assertThat(mvc.perform(get("/api/v1/order/all").param("sort", "createdAt,desc").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", admin))).andReturn().getResponse().getStatus()).isEqualTo(200);

        // the refund_required state: paid but order closed
        jdbc.update("UPDATE tenant_acme.orders SET status = 'CANCELLED' WHERE order_number = ?", o.orderNumber());
        jdbc.update("UPDATE tenant_acme.payments SET status = 'COMPLETED', refund_required = true");
        JsonNode list = body(mvc.perform(get("/api/v1/order/all").header("X-Tenant-ID", "acme").cookie(cookie("accessToken", admin))).andReturn());
        assertThat(list.at("/data/content/0/refundRequired").asBoolean()).isTrue();
        JsonNode detail = body(mvc.perform(get("/api/v1/order/" + o.orderNumber()).header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", admin))).andReturn());
        assertThat(detail.at("/data/refundRequired").asBoolean()).isTrue();
        assertThat(detail.at("/data/cancellable").asBoolean()).isFalse();
        JsonNode mine = body(mvc.perform(as(get("/api/v1/order/my-orders"), o.c())).andReturn());
        assertThat(mine.at("/data/content/0/refundRequired").asBoolean()).isTrue();
    }

    @Test
    void keyedReplayOfAnExpiredOpenOrderIsClosed409() throws Exception {
        Placed o = place("r3@acme.test", 1, "expiry-key-0001");
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - interval '5 hours'");
        MvcResult r = checkout(o.c(), "expiry-key-0001", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("IDEMPOTENT_ORDER_CLOSED");
    }

    @Test
    void successWebhookWithoutAmountIsIgnored_verifyAppliesProviderAnswer_cartEmptied() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("r4@acme.test", 2, null);
        assertThat(count(acme, "cart_items")).isEqualTo(1);
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();

        String noAmount = "{\"event\":\"charge.success\",\"data\":{\"id\":1,\"reference\":\"" + o.reference() + "\",\"status\":\"success\",\"channel\":\"card\"}}";
        assertThat(webhook(g, noAmount)).isEqualTo(200);
        Thread.sleep(1500);
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");

        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok", new PaystackVerifyTransactionResponse.Data(
                "success", o.reference(), BigDecimal.valueOf(minor), "GHS", "card", "Successful"));
        assertThat(body(verify(o.c(), o.reference())).at("/data/paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(count(acme, "cart_items")).as("cart emptied after payment").isZero();
    }

    @Test
    void expiresAtIsAnUtcInstantWithOffset() throws Exception {
        long p = product(acme, "Zone scent", "10.00", 5);
        Customer c = customer(acme, "r5@acme.test");
        cart(c, p, 1);
        String exp = body(checkout(c, null, null)).at("/data/expiresAt").asText();
        OffsetDateTime parsed = OffsetDateTime.parse(exp);
        assertThat(parsed.getOffset().getTotalSeconds()).isZero();
        assertThat(parsed).isAfter(OffsetDateTime.now().minusMinutes(1)).isBefore(OffsetDateTime.now().plusHours(2));
    }
}

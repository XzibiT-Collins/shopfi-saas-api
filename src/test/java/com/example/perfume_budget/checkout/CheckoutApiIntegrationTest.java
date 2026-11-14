package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** PAY-01 AC2, AC3 (idempotency), AC4 (callback_url), AC5 (preview, delivery detail), my-orders contract. Tenants acme + bravo. */
class CheckoutApiIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Customer ready(String email, long productId, int qty) {
        Customer c = customer(acme, email);
        cart(c, productId, qty);
        return c;
    }

    @Test
    void checkoutReturnsOrderNumberIdReferenceAndPaystackFields() throws Exception {
        long p = product(acme, "Scent One", "100.00", 10);
        Customer c = ready("a1@acme.test", p, 2);

        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("orderNumber").asText()).isNotBlank();
        assertThat(d.get("orderId").asLong()).isPositive();
        assertThat(d.get("paymentStatus").asText()).isEqualTo("INITIATED");
        assertThat(d.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(d.at("/data/authorization_url").asText()).startsWith("https://checkout.paystack.com/");
        assertThat(d.at("/data/reference").asText()).startsWith("T");
        assertThat(d.get("expiresAt").asText()).isNotBlank();
        assertThat(layerRemaining(acme, p)).isEqualTo(8);

        // the order is findable by reference in list and detail
        String ref = d.at("/data/reference").asText();
        JsonNode list = body(mvc.perform(as(get("/api/v1/order/my-orders").param("sort", "createdAt,desc"), c)).andReturn());
        assertThat(list.at("/data/content/0/reference").asText()).isEqualTo(ref);
        JsonNode detail = body(mvc.perform(as(get("/api/v1/order/" + d.get("orderNumber").asText()), c)).andReturn());
        assertThat(detail.at("/data/reference").asText()).isEqualTo(ref);
        assertThat(detail.at("/data/cancellable").asBoolean()).isTrue();
    }

    @Test
    void callbackUrlComesFromTheTenantLinkBuilderOnly() throws Exception {
        long p = product(acme, "Scent Two", "10.00", 5);
        Customer c = ready("a2@acme.test", p, 1);

        // hostile client-controlled hints must not influence the URL
        // a foreign Origin is refused outright (no order, no provider call); forwarded-host style hints are ignored
        assertThat(mvc.perform(as(post("/api/v1/cart/checkout").header("Origin", "https://evil.example"), c)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(initiated).isEmpty();
        mvc.perform(as(post("/api/v1/cart/checkout").header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http").header("Referer", "https://evil.example/x"), c)).andReturn();
        assertThat(initiated).hasSize(1);
        String cb = initiated.get(0).callbackUrl();
        assertThat(cb).endsWith("://" + ACME_DOMAIN + "/checkout/callback");
        assertThat(cb).doesNotContain("evil");
    }

    @Test
    void callbackUrlIsPerTenant() throws Exception {
        long pa = product(acme, "Scent A", "10.00", 5);
        long pb = product(bravo, "Scent B", "10.00", 5);
        checkout(ready("a3@acme.test", pa, 1), null, null);
        Customer cb = customer(bravo, "b3@bravo.test");
        cart(cb, pb, 1);
        checkout(cb, null, null);
        assertThat(initiated).hasSize(2);
        assertThat(initiated.get(0).callbackUrl()).contains(ACME_DOMAIN).doesNotContain(BRAVO_DOMAIN);
        assertThat(initiated.get(1).callbackUrl()).contains(BRAVO_DOMAIN).doesNotContain(ACME_DOMAIN);
    }

    @Test
    void sameKeyTwiceCreatesOneOrderAndOneProviderCall() throws Exception {
        long p = product(acme, "Scent Three", "50.00", 10);
        Customer c = ready("a4@acme.test", p, 2);

        JsonNode first = body(checkout(c, "key-0001-abcd", null)).get("data");
        JsonNode second = body(checkout(c, "key-0001-abcd", null)).get("data");

        assertThat(second.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(second.at("/data/authorization_url").asText()).isEqualTo(first.at("/data/authorization_url").asText());
        assertThat(second.at("/data/reference").asText()).isEqualTo(first.at("/data/reference").asText());
        assertThat(second.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(8);
    }

    @Test
    void keyReusedForDifferentRequestIs422() throws Exception {
        long p = product(acme, "Scent Four", "50.00", 10);
        coupon(acme, "SAVE10", 10);
        Customer c = ready("a5@acme.test", p, 1);
        assertThat(checkout(c, "key-0002-abcd", null).getResponse().getStatus()).isEqualTo(200);

        MvcResult r = checkout(c, "key-0002-abcd", "couponCode=SAVE10");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count(acme, "orders")).isEqualTo(1);
    }

    @Test
    void keyForClosedOrderIs409AndInvalidKeyIs400() throws Exception {
        long p = product(acme, "Scent Five", "50.00", 10);
        Customer c = ready("a6@acme.test", p, 1);
        JsonNode first = body(checkout(c, "key-0003-abcd", null)).get("data");
        assertThat(cancel(c, first.get("orderNumber").asText()).getResponse().getStatus()).isEqualTo(200);

        MvcResult r = checkout(c, "key-0003-abcd", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("IDEMPOTENT_ORDER_CLOSED");
        assertThat(body(r).at("/data/orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());

        for (String bad : new String[]{"short", "has space in it", "x".repeat(65), "semi;colon-key1", "<script>alert(1)</script>"}) {
            MvcResult br = checkout(c, bad, null);
            assertThat(br.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(br).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        }
    }

    @Test
    void keyIsScopedPerCustomerAndPerTenant() throws Exception {
        long pa = product(acme, "Scent Six", "50.00", 10);
        long pb = product(bravo, "Scent Seven", "50.00", 10);
        Customer c1 = ready("a7@acme.test", pa, 1);
        Customer c2 = ready("a8@acme.test", pa, 1);
        Customer cb = customer(bravo, "b7@bravo.test");
        cart(cb, pb, 1);

        String n1 = body(checkout(c1, "shared-key-0001", null)).at("/data/orderNumber").asText();
        String n2 = body(checkout(c2, "shared-key-0001", null)).at("/data/orderNumber").asText();
        String n3 = body(checkout(cb, "shared-key-0001", null)).at("/data/orderNumber").asText();
        assertThat(List.of(n1, n2, n3)).doesNotHaveDuplicates();
        assertThat(count(acme, "orders")).isEqualTo(2);
        assertThat(count(bravo, "orders")).isEqualTo(1);
    }

    @Test
    void doubleSubmitWithoutKeyReusesTheOpenOrder() throws Exception {
        long p = product(acme, "Scent Eight", "50.00", 10);
        Customer c = ready("a9@acme.test", p, 1);
        String n1 = body(checkout(c, null, null)).at("/data/orderNumber").asText();
        JsonNode second = body(checkout(c, null, null)).get("data");
        assertThat(second.get("orderNumber").asText()).isEqualTo(n1);
        assertThat(second.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
    }

    @Test
    void changedCartWithoutKeyCreatesANewOrder() throws Exception {
        long p = product(acme, "Scent Nine", "50.00", 10);
        Customer c = ready("a10@acme.test", p, 1);
        checkout(c, null, null);
        cart(c, product(acme, "Scent Ten", "20.00", 5), 1);
        checkout(c, null, null);
        assertThat(count(acme, "orders")).isEqualTo(2);
    }

    @Test
    void concurrentDoubleCheckoutCreatesExactlyOneOrder_withAndWithoutKey() throws Exception {
        for (String key : new String[]{"conc-key-0001", null}) {
            jdbc.execute("TRUNCATE tenant_acme.orders, tenant_acme.carts, tenant_acme.products CASCADE");
            initiateCalls.set(0);
            initiateDelayMillis = 300;
            long p = product(acme, "Race Scent " + key, "50.00", 10);
            Customer c = customer(acme, "race" + (key == null ? "n" : "k") + System.nanoTime() + "@acme.test");
            cart(c, p, 2);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<MvcResult>> calls = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) calls.add(() -> checkout(c, key, null));
                List<Future<MvcResult>> fs = pool.invokeAll(calls);
                for (Future<MvcResult> f : fs) assertThat(f.get().getResponse().getStatus()).isEqualTo(200);
            } finally {
                pool.shutdownNow();
            }
            assertThat(count(acme, "orders")).as("orders (key=%s)", key).isEqualTo(1);
            assertThat(initiateCalls.get()).as("provider calls (key=%s)", key).isEqualTo(1);
            assertThat(layerRemaining(acme, p)).as("stock reserved once (key=%s)", key).isEqualTo(8);
            assertThat(reservedQty(acme, p)).isEqualTo(2);
        }
    }

    @Test
    void explicitDeliveryDetailIsUsedAndOwnerScoped() throws Exception {
        long p = product(acme, "Scent Eleven", "50.00", 10);
        Customer c = ready("a11@acme.test", p, 1);
        Customer other = customer(acme, "a12@acme.test");
        long mine = extraAddress(c);
        long theirs = extraAddress(other);

        MvcResult foreign = checkout(c, null, "deliveryDetailId=" + theirs);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(foreign).at("/data/code").asText()).isEqualTo("DELIVERY_DETAIL_NOT_FOUND");
        MvcResult missing = checkout(c, null, "deliveryDetailId=99999999");
        assertThat(missing.getResponse().getContentAsString()).isEqualTo(foreign.getResponse().getContentAsString());
        assertThat(count(acme, "orders")).isZero();

        String n = body(checkout(c, null, "deliveryDetailId=" + mine)).at("/data/orderNumber").asText();
        JsonNode detail = body(mvc.perform(as(get("/api/v1/order/" + n), c)).andReturn());
        assertThat(detail.at("/data/deliveryDetail/id").asLong()).isEqualTo(mine);
        assertThat(detail.at("/data/deliveryDetail/city").asText()).isEqualTo("Kumasi");
    }

    @Test
    void previewMatchesCheckoutTotalAndDoesNotConsumeTheCoupon() throws Exception {
        long p = product(acme, "Scent Twelve", "100.00", 10);
        coupon(acme, "SAVE10", 10);
        Customer c = ready("a13@acme.test", p, 2);

        MvcResult pr = mvc.perform(as(post("/api/v1/cart/preview").contentType("application/json")
                .content("{\"couponCode\":\"save10\"}"), c)).andReturn();
        assertThat(pr.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = body(pr).get("data");
        assertThat(d.at("/coupon/valid").asBoolean()).isTrue();
        assertThat(d.get("subtotal").asText()).isEqualTo("GHS 200.00");
        assertThat(d.get("couponDiscountAmount").asText()).isEqualTo("GHS 20.00");
        assertThat(d.get("deliveryFee").asText()).isEqualTo("GHS 0.00");
        assertThat(jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code='SAVE10'", Integer.class)).isZero();
        assertThat(count(acme, "orders")).isZero();
        assertThat(count(acme, "inventory_allocations")).as("preview reserves nothing").isZero();

        JsonNode co = body(checkout(c, null, "couponCode=SAVE10")).get("data");
        assertThat(co.get("totalAmount").asText()).isEqualTo(d.get("totalAmount").asText());
        assertThat(jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code='SAVE10'", Integer.class)).isEqualTo(1);
    }

    @Test
    void previewReportsInvalidCouponsWithoutRevealingWhichCodesExist() throws Exception {
        long p = product(acme, "Scent Thirteen", "100.00", 10);
        coupon(acme, "OLDONE", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET expiration_date = current_date - 5, start_date = current_date - 10 WHERE code = 'OLDONE'");
        Customer c = ready("a14@acme.test", p, 1);

        for (String code : new String[]{"OLDONE", "NOPE"}) {
            JsonNode d = body(mvc.perform(as(post("/api/v1/cart/preview").contentType("application/json")
                    .content("{\"couponCode\":\"" + code + "\"}"), c)).andReturn()).get("data");
            assertThat(d.at("/coupon/valid").asBoolean()).isFalse();
            assertThat(d.at("/coupon/reason").asText()).isEqualTo("COUPON_INVALID");
            assertThat(d.at("/coupon/message").asText()).isEqualTo("Invalid coupon code.");
            assertThat(d.get("couponDiscountAmount").asText()).isEqualTo("GHS 0.00");
        }
        // no body at all is allowed
        assertThat(mvc.perform(as(post("/api/v1/cart/preview"), c)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void cartLineTotalIsServerComputed() throws Exception {
        long p = product(acme, "Scent Fourteen", "12.50", 10);
        Customer c = ready("a15@acme.test", p, 3);
        JsonNode cartJson = body(mvc.perform(as(get("/api/v1/cart"), c)).andReturn());
        assertThat(cartJson.at("/data/cartItems/0/lineTotal").asText()).isEqualTo("GHS 37.50");
    }

    @Test
    void myOrdersSortContract() throws Exception {
        long p = product(acme, "Scent Fifteen", "10.00", 10);
        Customer c = ready("a16@acme.test", p, 1);
        checkout(c, null, null);
        assertThat(mvc.perform(as(get("/api/v1/order/my-orders").param("sort", "createdAt,desc"), c)).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String bad : new String[]{"nonsense,asc", "user.password,asc", "totalAmount.amount,desc", "id;drop table orders,asc"}) {
            MvcResult r = mvc.perform(as(get("/api/v1/order/my-orders").param("sort", bad), c)).andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(r).at("/data/code").asText()).isEqualTo("INVALID_SORT");
        }
    }

    @Test
    void ordersAndReferencesOfTenantAAreInvisibleOnTenantB() throws Exception {
        long pa = product(acme, "Scent Sixteen", "10.00", 10);
        Customer a = ready("a17@acme.test", pa, 1);
        JsonNode co = body(checkout(a, null, null)).get("data");
        String ref = co.at("/data/reference").asText();
        String orderNumber = co.get("orderNumber").asText();

        // a customer of B with the SAME email asks for A's reference / order number
        Customer b = customer(bravo, "a17@acme.test");
        MvcResult v = verify(b, ref);
        MvcResult unknown = verify(b, "T000000000000");
        assertThat(v.getResponse().getStatus()).isEqualTo(404);
        assertThat(v.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(mvc.perform(as(get("/api/v1/order/" + orderNumber), b)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(cancel(b, orderNumber).getResponse().getStatus()).isEqualTo(404);
        JsonNode list = body(mvc.perform(as(get("/api/v1/order/my-orders"), b)).andReturn());
        assertThat(list.at("/data/content")).isEmpty();
        assertThat(orderStatus(acme, orderNumber)).isEqualTo("INITIATED");
        // A's cookie replayed on B's host is rejected by the tenant binding
        MvcResult replay = mvc.perform(get("/api/v1/payment/verify").param("reference", ref)
                .header("X-Tenant-ID", "bravo").cookie(cookie("accessToken", a.token()))).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
    }
}

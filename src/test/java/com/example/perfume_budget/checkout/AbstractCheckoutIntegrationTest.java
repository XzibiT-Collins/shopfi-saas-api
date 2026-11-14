package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenantauth.AbstractTenantWebTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PAY-01 base: real app + Postgres, tenants acme and bravo, customers/products/carts seeded with SQL, Paystack
 * STUBBED (no network, no real credentials; per-tenant secrets are fake test strings).
 */
public abstract class AbstractCheckoutIntegrationTest extends AbstractTenantWebTest {

    @MockitoBean
    protected PaymentGatewayAPIService gateway;

    protected final List<PaystackInitiatePaymentRequest> initiated = new CopyOnWriteArrayList<>();
    protected final AtomicInteger initiateCalls = new AtomicInteger();
    protected volatile long initiateDelayMillis = 0;
    protected volatile PaystackVerifyTransactionResponse verifyAnswer = null;
    protected final AtomicInteger verifyCalls = new AtomicInteger();

    @BeforeEach
    void stubGateway() {
        reset(gateway);
        initiated.clear();
        initiateCalls.set(0);
        verifyCalls.set(0);
        verifyAnswer = null;
        initiateDelayMillis = 0;
        when(gateway.initiatePaystackTransaction(any())).thenAnswer(inv -> {
            initiated.add(inv.getArgument(0));
            initiateCalls.incrementAndGet();
            if (initiateDelayMillis > 0) Thread.sleep(initiateDelayMillis);
            String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            return new PaystackInitiateTransactionResponse(true, "Authorization URL created",
                    new PaystackInitiateTransactionResponse.Data("https://checkout.paystack.com/" + code, code, "T" + code));
        });
        when(gateway.verifyPaystackTransaction(anyString())).thenAnswer(inv -> {
            verifyCalls.incrementAndGet();
            if (verifyAnswer == null) throw new PaymentException("unreachable");
            return verifyAnswer;
        });
    }

    @org.junit.jupiter.api.AfterEach
    void cleanCheckoutData() {
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.execute("TRUNCATE " + schema + ".orders, " + schema + ".carts, " + schema + ".delivery_details, "
                    + schema + ".products, " + schema + ".journal_entries, " + schema + ".coupons CASCADE");
        }
    }

    // ---- seeding ----

    protected record Customer(TestTenant tenant, long id, String email, String token) {}

    protected Customer customer(TestTenant t, String email) {
        Long id = jdbc.queryForObject("INSERT INTO " + t.schemaName() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Cust', ?, 'x', 'CUSTOMER', true, true, now()) RETURNING id", Long.class, email);
        jdbc.update("INSERT INTO " + t.schemaName() + ".delivery_details (user_id, recipient_name, phone_number, address_line1, city, region, label, is_default, created_at) "
                + "VALUES (?, 'Cust', '0200000000', '1 Main St', 'Accra', 'Greater Accra', 'HOME', true, now())", id);
        String token = tenants.runAs(t, () -> jwtUtil.generateAccessToken(email, "CUSTOMER", id, "Cust"));
        return new Customer(t, id, email, token);
    }

    protected long extraAddress(Customer c) {
        return jdbc.queryForObject("INSERT INTO " + c.tenant().schemaName() + ".delivery_details (user_id, recipient_name, phone_number, address_line1, city, region, label, is_default, created_at) "
                + "VALUES (?, 'Other', '0200000001', '2 Side St', 'Kumasi', 'Ashanti', 'WORK', false, now()) RETURNING id", Long.class, c.id());
    }

    protected long product(TestTenant t, String name, String price, int stock) {
        String slug = name.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID().toString().substring(0, 6);
        return jdbc.queryForObject("INSERT INTO " + t.schemaName() + ".products (version, name, slug, sku, stock_quantity, sold_count, is_active, is_enlisted, "
                + "price_amount, price_currency, created_at) VALUES (0, ?, ?, ?, ?, 0, true, true, ?, 'GHS', now()) RETURNING id",
                Long.class, name, slug, slug, stock, new BigDecimal(price));
    }

    protected void cart(Customer c, long productId, int qty) {
        String s = c.tenant().schemaName();
        Long cartId = jdbc.queryForList("SELECT id FROM " + s + ".carts WHERE user_id = ?", Long.class, c.id()).stream().findFirst().orElse(null);
        if (cartId == null) {
            cartId = jdbc.queryForObject("INSERT INTO " + s + ".carts (user_id, created_at) VALUES (?, now()) RETURNING id", Long.class, c.id());
        }
        jdbc.update("INSERT INTO " + s + ".cart_items (cart_id, product_id, quantity, created_at, amount, currency_code) VALUES (?, ?, ?, now(), 0, 'GHS')",
                cartId, productId, qty);
    }

    protected void coupon(TestTenant t, String code, int percent) {
        jdbc.update("INSERT INTO " + t.schemaName() + ".coupons (code, discount_type, discount_value, is_active, start_date, expiration_date, usage_count, usage_limit, created_at) "
                + "VALUES (?, 'PERCENTAGE', ?, true, current_date - 1, current_date + 30, 0, 100, now())", code, new BigDecimal(percent));
    }

    // ---- stock / state probes ----

    protected int layerRemaining(TestTenant t, long productId) {
        return jdbc.queryForObject("SELECT coalesce(sum(remaining_quantity),0) FROM " + t.schemaName() + ".inventory_layers WHERE product_id = ?", Integer.class, productId);
    }

    protected int reservedQty(TestTenant t, long productId) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM " + t.schemaName() + ".inventory_allocations WHERE product_id = ? AND status = 'RESERVED'", Integer.class, productId);
    }

    protected int count(TestTenant t, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + t.schemaName() + "." + table, Integer.class);
    }

    protected String orderStatus(TestTenant t, String orderNumber) {
        return jdbc.queryForObject("SELECT status FROM " + t.schemaName() + ".orders WHERE order_number = ?", String.class, orderNumber);
    }

    // ---- http ----

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Customer c) {
        return b.header("X-Tenant-ID", c.tenant().slug()).cookie(cookie("accessToken", c.token()));
    }

    protected MvcResult checkout(Customer c, String key, String query) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/v1/cart/checkout" + (query == null ? "" : "?" + query));
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(as(b, c)).andReturn();
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected MvcResult verify(Customer c, String reference) throws Exception {
        return mvc.perform(as(get("/api/v1/payment/verify").param("reference", reference), c)).andReturn();
    }

    protected MvcResult cancel(Customer c, String orderNumber) throws Exception {
        return mvc.perform(as(post("/api/v1/order/" + orderNumber + "/cancel"), c)).andReturn();
    }

    // ---- webhook ----

    protected record Gateway(String token, String secret) {}

    /** Saves a fake secret for the tenant through the admin API (also mints its webhook token). */
    protected Gateway configureGateway(TestTenant t) throws Exception {
        String secret = "sk_test_FAKE_" + t.slug() + "_" + UUID.randomUUID().toString().replace("-", "");
        String admin = setCookieValue(loginPassword(t.slug(), t.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        MvcResult r = mvc.perform(put("/api/v1/payment-settings").contentType("application/json").header("X-Tenant-ID", t.slug())
                .cookie(cookie("accessToken", admin))
                .content(json.writeValueAsString(Map.of("secretKey", secret, "publicKey", "pk_test_FAKE", "enabled", true)))).andReturn();
        String url = json.readTree(r.getResponse().getContentAsString()).at("/data/webhookUrl").asText();
        return new Gateway(url.substring(url.lastIndexOf('/') + 1), secret);
    }

    protected int webhook(Gateway g, String body) throws Exception {
        return mvc.perform(post("/api/v1/webhook/" + g.token()).contentType("application/json")
                .header("X-Paystack-Signature", hmac512(body, g.secret())).content(body)).andReturn().getResponse().getStatus();
    }

    protected static String event(String type, String reference, long amountMinor) {
        return "{\"event\":\"" + type + "\",\"data\":{\"id\":1,\"reference\":\"" + reference + "\",\"status\":\""
                + ("charge.success".equals(type) ? "success" : "failed") + "\",\"amount\":" + amountMinor
                + ",\"currency\":\"GHS\",\"channel\":\"card\",\"message\":\"m\"}}";
    }

    protected static String hmac512(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    protected List<String> orderNumbers(TestTenant t) {
        return new ArrayList<>(jdbc.queryForList("SELECT order_number FROM " + t.schemaName() + ".orders ORDER BY id", String.class));
    }
}

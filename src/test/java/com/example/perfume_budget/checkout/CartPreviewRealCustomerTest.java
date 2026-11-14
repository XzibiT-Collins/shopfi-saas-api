package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-03b F10: POST /cart/preview for a customer who has no cart row yet. The customer here is a REAL one (registered
 * and OTP-verified through the API, not a directly inserted fixture). The preview runs in a read-only transaction, so
 * lazily creating the missing cart there used to fail with "cannot execute INSERT in a read-only transaction" (500).
 * Expected: the normal 400 "Cart is empty" and no cart row written by the preview.
 */
class CartPreviewRealCustomerTest extends AbstractCheckoutIntegrationTest {

    private String registerAndLogin(String email) throws Exception {
        Map<String, String> body = Map.of("email", email, "fullName", "Real Shopper",
                "password", "Sup3rSecret!pw", "confirmPassword", "Sup3rSecret!pw");
        assertThat(mvc.perform(postJson("/api/v1/auth/register", "acme", body)).andReturn().getResponse().getStatus()).isEqualTo(204);
        String otp = redis.entrySet().stream()
                .filter(e -> e.getKey().startsWith("otp:registration:") && e.getKey().endsWith(":" + email))
                .map(e -> (String) e.getValue()).findFirst().orElse(null);
        assertThat(otp).as("registration OTP captured").isNotNull();
        MvcResult v = mvc.perform(postJson("/api/v1/auth/verify-otp", "acme", Map.of("email", email, "otp", otp))).andReturn();
        assertThat(v.getResponse().getStatus()).isEqualTo(200);
        String token = setCookieValue(v, "accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private int carts(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts c JOIN tenant_acme.users u ON u.id = c.user_id WHERE u.email = ?",
                Integer.class, email);
    }

    @Test
    void previewForARealCustomerWithoutACartRowIsA400EmptyCart_notA500() throws Exception {
        String email = "real-" + UUID.randomUUID() + "@example.com";
        String token = registerAndLogin(email);
        assertThat(carts(email)).as("a freshly registered customer has no cart row").isZero();

        MvcResult r = mvc.perform(post("/api/v1/cart/preview").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", token))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("Cart is empty");
        assertThat(carts(email)).as("the read-only preview must not insert a cart").isZero();
    }

    @Test
    void previewWorksAfterTheCartIsCreatedAndStillReadOnly() throws Exception {
        String email = "real2-" + UUID.randomUUID() + "@example.com";
        String token = registerAndLogin(email);
        long p = product(acme, "Scent Real", "10.00", 5);
        long uid = jdbc.queryForObject("SELECT id FROM tenant_acme.users WHERE email = ?", Long.class, email);
        // the first cart call (GET /cart) creates the row in a writable transaction
        assertThat(mvc.perform(get("/api/v1/cart").header("X-Tenant-ID", "acme").cookie(cookie("accessToken", token)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(carts(email)).isEqualTo(1);
        jdbc.update("INSERT INTO tenant_acme.cart_items (cart_id, product_id, quantity, created_at, amount, currency_code) "
                + "SELECT id, ?, 2, now(), 0, 'GHS' FROM tenant_acme.carts WHERE user_id = ?", p, uid);
        MvcResult r = mvc.perform(post("/api/v1/cart/preview").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", token))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("subtotal").asText()).isEqualTo("GHS 20.00");
    }
}

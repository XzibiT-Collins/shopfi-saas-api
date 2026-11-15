package com.example.perfume_budget.checkout;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** SEC-03b: the first cart row of a customer is created by the first writable cart call (add-item); preview never creates it. */
class FirstCartCreationTest extends AbstractCheckoutIntegrationTest {

    @Test
    void customerWithoutACartRow_previewIs400_thenAddItemCreatesTheCart_thenCartAndPreviewWork() throws Exception {
        long p = product(acme, "First Cart Scent", "10.00", 5);
        Customer c = customer(acme, "firstcart@acme.test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id())).isZero();

        MvcResult empty = mvc.perform(as(post("/api/v1/cart/preview"), c)).andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        assertThat(empty.getResponse().getContentAsString()).contains("Cart is empty");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id())).isZero();

        MvcResult add = mvc.perform(as(post("/api/v1/cart/items/add-item").contentType("application/json")
                .content("{\"productId\":" + p + ",\"quantity\":2}"), c)).andReturn();
        assertThat(add.getResponse().getStatus()).as(add.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id())).isEqualTo(1);

        MvcResult cart = mvc.perform(as(get("/api/v1/cart"), c)).andReturn();
        assertThat(cart.getResponse().getStatus()).isEqualTo(200);
        assertThat(cart.getResponse().getContentAsString()).contains("First Cart Scent");
        MvcResult preview = mvc.perform(as(post("/api/v1/cart/preview"), c)).andReturn();
        assertThat(preview.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(preview).at("/data/subtotal").asText()).isEqualTo("GHS 20.00");
    }
}

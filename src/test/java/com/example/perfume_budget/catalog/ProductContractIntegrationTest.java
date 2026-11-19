package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * API-03: the catalog write contract against the full app: numeric money and category slug in ProductDetails, minimum
 * constraints (400), 415 for JSON on a multipart route, product image type / size checks (before any upload).
 * Rows use the product name "Qct" (family prefix QCT) and are removed after each test.
 */
class ProductContractIntegrationTest extends AbstractCheckoutIntegrationTest {

    @BeforeEach
    @AfterEach
    void dropRows() {
        jdbc.update("UPDATE tenant_acme.product_families SET base_unit_id = NULL WHERE family_code LIKE 'QCT-%'");
        jdbc.update("UPDATE tenant_acme.products SET category_id = NULL WHERE sku LIKE 'QCT-%'");
        jdbc.update("DELETE FROM tenant_acme.products WHERE sku LIKE 'QCT-%'");
        jdbc.update("DELETE FROM tenant_acme.product_families WHERE family_code LIKE 'QCT-%'");
        jdbc.update("DELETE FROM tenant_acme.categories WHERE slug = 'qct-category'");
    }

    private Cookie admin() {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        return new Cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name"))));
    }

    private MockMultipartHttpServletRequestBuilder create(String selling, String cost) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/product/add-product")
                .param("productName", "Qct").param("currency", "GHS").param("sellingPrice", selling).param("costPrice", cost)
                .param("isNewProduct", "true").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(admin());
    }

    private MvcResult run(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    @Test
    void productDetailsCarryNumericMoneyAndCategorySlug() throws Exception {
        long category = jdbc.queryForObject("INSERT INTO tenant_acme.categories (name, slug, description, created_at) "
                + "VALUES ('Qct Category', 'qct-category', 'd', now()) RETURNING id", Long.class);
        MvcResult created = run(create("45.5", "30").param("categoryId", String.valueOf(category)));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(created).get("data");
        assertThat(d.get("sellingPrice").asText()).isEqualTo("GHS 45.50");
        assertThat(d.get("sellingPriceAmount").isNumber()).isTrue();
        assertThat(d.get("sellingPriceAmount").decimalValue()).isEqualByComparingTo("45.50");
        assertThat(d.get("originalPriceAmount").decimalValue()).isEqualByComparingTo("45.50");
        assertThat(d.get("costPriceAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(d.get("currency").asText()).isEqualTo("GHS");
        assertThat(d.at("/category/slug").asText()).isEqualTo("qct-category");

        // the admin read of the same product answers the same numbers, with a discount applied to the effective price only
        long id = d.get("productId").asLong();
        String disc = "{\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"startAt\":\"2020-01-01T00:00:00\",\"endAt\":\"2090-01-01T00:00:00\"}";
        assertThat(run(MockMvcRequestBuilders.put("/api/v1/product/admin/" + id + "/discount").contentType(MediaType.APPLICATION_JSON)
                .content(disc).header("X-Tenant-ID", "acme").cookie(admin())).getResponse().getStatus()).isEqualTo(200);
        MvcResult read = run(MockMvcRequestBuilders.get("/api/v1/product/main/" + id).header("X-Tenant-ID", "acme").cookie(admin()));
        JsonNode r = body(read).get("data");
        assertThat(r.get("sellingPriceAmount").decimalValue()).isEqualByComparingTo("40.95");
        assertThat(r.get("originalPriceAmount").decimalValue()).isEqualByComparingTo("45.50");
        assertThat(r.get("costPriceAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(r.get("onSale").asBoolean()).isTrue();
    }

    @Test
    void negativeAndBelowMinimumValuesAre400WithTheFieldNamed() throws Exception {
        String[][] bad = {
                {"sellingPrice", "-0.01"}, {"costPrice", "-5"}, {"lowStockThreshold", "-1"}, {"conversionFactor", "0"}};
        for (String[] f : bad) {
            var b = create("10", "5").param(f[0], f[1]);
            if (f[0].equals("sellingPrice") || f[0].equals("costPrice")) {
                b = create(f[0].equals("sellingPrice") ? f[1] : "10", f[0].equals("costPrice") ? f[1] : "5");
            }
            MvcResult r = run(b);
            assertThat(r.getResponse().getStatus()).as(f[0] + "=" + f[1] + " " + r.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(body(r).at("/data/" + f[0]).isMissingNode()).as(f[0] + " named in data").isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE sku LIKE 'QCT-%'", Integer.class)).isZero();
        // the boundaries are accepted
        MvcResult zero = run(create("0", "0").param("lowStockThreshold", "0"));
        assertThat(zero.getResponse().getStatus()).as(zero.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void jsonToAMultipartRouteIs415_notA500() throws Exception {
        String json = "{\"productName\":\"Qct\",\"currency\":\"GHS\",\"sellingPrice\":10,\"costPrice\":5,\"isNewProduct\":true}";
        MvcResult add = run(MockMvcRequestBuilders.post("/api/v1/product/add-product").contentType(MediaType.APPLICATION_JSON)
                .content(json).header("X-Tenant-ID", "acme").cookie(admin()));
        assertThat(add.getResponse().getStatus()).as(add.getResponse().getContentAsString()).isEqualTo(415);
        assertThat(body(add).get("description").asText()).isEqualTo("Unsupported content type");
        MvcResult update = run(MockMvcRequestBuilders.put("/api/v1/product/update/1").contentType(MediaType.APPLICATION_JSON)
                .content(json).header("X-Tenant-ID", "acme").cookie(admin()));
        assertThat(update.getResponse().getStatus()).isEqualTo(415);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE sku LIKE 'QCT-%'", Integer.class)).isZero();
    }

    @Test
    void aProductImageThatIsNotAnImageIs400_andNothingIsSaved() throws Exception {
        for (String[] f : new String[][]{{"x.jpg", "image/jpeg", "<svg xmlns='http://www.w3.org/2000/svg' onload='1'/>"},
                {"x.png", "image/png", "%PDF-1.7 not an image"}, {"x.webp", "image/webp", "plain text"}}) {
            MvcResult r = run(create("10", "5").file(new MockMultipartFile("productImage", f[0], f[1], f[2].getBytes())));
            assertThat(r.getResponse().getStatus()).as(f[0] + " " + r.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(body(r).get("description").asText()).isEqualTo("Product image must be a PNG, JPEG or WebP image");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE sku LIKE 'QCT-%'", Integer.class)).isZero();
    }

    @Test
    void aProductImageOverFiveMegabytesIs413_andNothingIsSaved() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0x89; big[1] = 'P'; big[2] = 'N'; big[3] = 'G'; big[4] = 0x0D; big[5] = 0x0A; big[6] = 0x1A; big[7] = 0x0A;
        MvcResult r = run(create("10", "5").file(new MockMultipartFile("productImage", "big.png", "image/png", big)));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE sku LIKE 'QCT-%'", Integer.class)).isZero();
    }
}

package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * API-03 (UI-08a review F1, MEDIUM): {@code DELETE /product/delete/{id}} on a product created through the API
 * (isNewProduct=true: the family's base unit) always hit FK_PRODUCT_FAMILIES_ON_BASE_UNIT and answered 409. Real app and
 * Postgres. Rows use the product name "Qdl" (family prefix QDL) and are removed after each test.
 */
class ProductDeleteIntegrationTest extends AbstractCheckoutIntegrationTest {

    @BeforeEach
    @AfterEach
    void dropQdlRows() {
        jdbc.update("DELETE FROM tenant_acme.cart_items WHERE product_id IN (SELECT id FROM tenant_acme.products WHERE sku LIKE 'QDL-%')");
        jdbc.update("UPDATE tenant_acme.product_families SET base_unit_id = NULL WHERE family_code LIKE 'QDL-%'");
        jdbc.update("DELETE FROM tenant_acme.products WHERE sku LIKE 'QDL-%'");
        jdbc.update("DELETE FROM tenant_acme.product_families WHERE family_code LIKE 'QDL-%'");
    }

    private Cookie admin() {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        return new Cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name"))));
    }

    private JsonNode createBase() throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/product/add-product")
                .param("productName", "Qdl").param("currency", "GHS").param("sellingPrice", "100.00").param("costPrice", "60.00")
                .param("isNewProduct", "true").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(admin())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private long familyOf(long productId) {
        return jdbc.queryForObject("SELECT family_id FROM tenant_acme.products WHERE id = ?", Long.class, productId);
    }

    private long createVariant(long familyId, String uom) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/product/add-product")
                .param("productName", "Qdl").param("currency", "GHS").param("sellingPrice", "500.00").param("costPrice", "0")
                .param("isNewProduct", "false").param("familyId", String.valueOf(familyId)).param("uomCode", uom)
                .param("conversionFactor", "6").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(admin())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data").get("productId").asLong();
    }

    private MvcResult delete(long id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/product/delete/" + id)
                .header("X-Tenant-ID", "acme").cookie(admin())).andReturn();
    }

    private int rows(String table, String where, Object arg) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme." + table + " WHERE " + where, Integer.class, arg);
    }

    @Test
    void aProductCreatedThroughTheApiCanBeDeleted_itsOwnFamilyGoesWithIt() throws Exception {
        long id = createBase().get("productId").asLong();
        long family = familyOf(id);
        assertThat(rows("product_families", "base_unit_id = ?", id)).as("wired as base unit").isEqualTo(1);

        assertThat(delete(id).getResponse().getStatus()).isEqualTo(204);

        assertThat(rows("products", "id = ?", id)).isZero();
        assertThat(rows("product_families", "id = ?", family)).as("family removed with its only member").isZero();
    }

    @Test
    void aBaseUnitWithVariantsAnswers409FamilyHasVariants_andNothingChanges_untilTheVariantsAreDeleted() throws Exception {
        long base = createBase().get("productId").asLong();
        long family = familyOf(base);
        long variant = createVariant(family, "BOX");

        MvcResult blocked = delete(base);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(blocked).at("/data/code").asText()).isEqualTo("FAMILY_HAS_VARIANTS");
        assertThat(rows("products", "id = ?", base)).isEqualTo(1);
        assertThat(rows("product_families", "base_unit_id = ?", base)).as("still the base unit").isEqualTo(1);

        assertThat(delete(variant).getResponse().getStatus()).isEqualTo(204);
        assertThat(rows("products", "id = ?", variant)).isZero();
        assertThat(rows("product_families", "id = ?", family)).as("family kept: base unit still there").isEqualTo(1);

        assertThat(delete(base).getResponse().getStatus()).isEqualTo(204);
        assertThat(rows("products", "id = ?", base)).isZero();
        assertThat(rows("product_families", "id = ?", family)).isZero();
    }

    @Test
    void aProductInACart_staysA409ConstraintViolation_andTheFamilyIsNotHalfDeleted() throws Exception {
        long id = createBase().get("productId").asLong();
        long family = familyOf(id);
        Customer c = customer(acme, "delete-cart@acme.test");
        cart(c, id, 1);

        MvcResult r = delete(id);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).at("/data/code").asText()).isNotEqualTo("FAMILY_HAS_VARIANTS");
        // the transaction rolled back as a whole: product, family and the base-unit link are all still there
        assertThat(rows("products", "id = ?", id)).isEqualTo(1);
        assertThat(rows("product_families", "id = ?", family)).isEqualTo(1);
        assertThat(rows("product_families", "base_unit_id = ?", id)).isEqualTo(1);
    }

    @Test
    void anUnknownIdIsAnIdempotent204() throws Exception {
        assertThat(delete(987654321L).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void aSeededProductWithoutAFamilyStillDeletes() throws Exception {
        long id = product(acme, "Qdl Plain", "10.00", 0);
        jdbc.update("UPDATE tenant_acme.products SET sku = 'QDL-PLAIN-EA' WHERE id = ?", id);
        assertThat(delete(id).getResponse().getStatus()).isEqualTo(204);
        assertThat(rows("products", "id = ?", id)).isZero();
    }

    private MvcResult addVariant(long familyId, String uom) throws Exception {
        return mvc.perform(multipart("/api/v1/product/add-product")
                .param("productName", "Qdl").param("currency", "GHS").param("sellingPrice", "500.00").param("costPrice", "0")
                .param("isNewProduct", "false").param("familyId", String.valueOf(familyId)).param("uomCode", uom)
                .param("conversionFactor", "6").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(admin())).andReturn();
    }

    /**
     * FIX-C review low / FIX-B: deleting the family's base unit while another admin adds a variant to the same family.
     * Whatever the interleaving, exactly one of them wins, neither answers 5xx, and the rows are consistent: either the
     * family and its base unit are gone with no variant left behind, or the family keeps its base unit and the variant.
     * (Lock analysis in docs/api/checkout.md: the delete locks the family row, then the product row; nothing waits for
     * the other in a cycle, the loser's foreign key check fails and answers 409.)
     */
    @Test
    void deleteRacingAddVariantOnOneFamily_oneWins_neverA500_andTheStateIsConsistent() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        int deleteWon = 0;
        int addWon = 0;
        try {
            for (int round = 0; round < 24; round++) {
                long base = createBase().get("productId").asLong();
                long family = familyOf(base);
                java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<MvcResult> del = pool.submit(() -> {
                    go.await();
                    return delete(base);
                });
                java.util.concurrent.Future<MvcResult> add = pool.submit(() -> {
                    go.await();
                    return addVariant(family, "BOX");
                });
                MvcResult d = del.get(60, java.util.concurrent.TimeUnit.SECONDS);
                MvcResult a = add.get(60, java.util.concurrent.TimeUnit.SECONDS);
                int ds = d.getResponse().getStatus();
                int as = a.getResponse().getStatus();
                String ctx = "round " + round + ": delete " + ds + " " + d.getResponse().getContentAsString()
                        + " / add " + as + " " + a.getResponse().getContentAsString();
                assertThat(ds).as(ctx).isIn(204, 409);
                assertThat(as).as(ctx).isIn(200, 400, 404, 409);
                assertThat((ds == 204) != (as == 200)).as("exactly one wins: " + ctx).isTrue();
                if (ds == 204) {
                    deleteWon++;
                    assertThat(rows("product_families", "id = ?", family)).as(ctx).isZero();
                    assertThat(rows("products", "family_id = ?", family)).as("no orphan variant: " + ctx).isZero();
                    assertThat(rows("products", "id = ?", base)).as(ctx).isZero();
                } else {
                    addWon++;
                    assertThat(rows("product_families", "id = ?", family)).as(ctx).isEqualTo(1);
                    assertThat(rows("product_families", "base_unit_id = ?", base)).as("still the base unit: " + ctx).isEqualTo(1);
                    assertThat(rows("products", "family_id = ?", family)).as("base + variant: " + ctx).isEqualTo(2);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // information for the PR: both outcomes normally occur; the assertion above is the contract
        System.out.println("delete-vs-add-variant race: delete won " + deleteWon + ", add won " + addWon);
    }
}

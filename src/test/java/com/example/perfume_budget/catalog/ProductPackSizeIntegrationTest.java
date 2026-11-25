package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-BE-01: familyId / isBaseUnit / conversionFactor / uomCode on the ADMIN product rows (listing, search, detail) and
 * conversionFactor / stockQuantity / uomCode on the reverse-conversion variants. Fixture (tenant acme): family PSZ-1
 * with a base unit "Psz Oil" (EA, factor 1) and a BOX (factor 6); "Psz Loose" has no family; bravo has its own family.
 * Public responses must keep their exact key sets.
 */
class ProductPackSizeIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final List<String> PACK_FIELDS = List.of("familyId", "isBaseUnit", "conversionFactor", "uomCode");
    static final List<String> PUBLIC_ROW_KEYS = List.of("productId", "productName", "productShortDescription", "productImageUrl",
            "categoryName", "price", "originalPrice", "onSale", "discountPercentage", "discountEndsAt", "isOutOfStock", "slug");
    static final List<String> PUBLIC_PAGE_KEYS = List.of("productId", "productName", "productShortDescription", "productDescription",
            "productImageUrl", "category", "sellingPrice", "originalPrice", "onSale", "discountPercentage", "discountEndsAt",
            "isOutOfStock", "isFeatured", "slug");

    long familyId, baseId, boxId, looseId, bravoFamilyId, bravoBoxId;
    String baseSlug, boxSlug, looseSlug;
    Cookie admin, frontDesk, frontDeskNoPerm, bravoAdmin;
    List<Map<String, Object>> templateBefore;

    @BeforeEach
    void seed() {
        cleanup();
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("INSERT INTO " + schema + ".units_of_measure (code, name) VALUES ('EA', 'Each') ON CONFLICT (code) DO NOTHING");
            jdbc.update("INSERT INTO " + schema + ".units_of_measure (code, name) VALUES ('BOX', 'Box') ON CONFLICT (code) DO NOTHING");
        }
        familyId = family("tenant_acme", "PSZ-1");
        baseId = unit("tenant_acme", "Psz Oil", "PSZ-1-EA", 40, familyId, "EA", 1, true);
        boxId = unit("tenant_acme", "Psz Oil Box", "PSZ-1-BOX", 7, familyId, "BOX", 6, false);
        jdbc.update("UPDATE tenant_acme.product_families SET base_unit_id = ? WHERE id = ?", baseId, familyId);
        looseId = unit("tenant_acme", "Psz Loose", "PSZ-LOOSE", 3, null, null, 1, true);
        bravoFamilyId = family("tenant_bravo", "PSZ-9");
        long bravoBase = unit("tenant_bravo", "Psz Bravo", "PSZ-9-EA", 5, bravoFamilyId, "EA", 1, true);
        bravoBoxId = unit("tenant_bravo", "Psz Bravo Box", "PSZ-9-BOX", 2, bravoFamilyId, "BOX", 24, false);
        jdbc.update("UPDATE tenant_bravo.product_families SET base_unit_id = ? WHERE id = ?", bravoBase, bravoFamilyId);
        baseSlug = slug("tenant_acme", baseId);
        boxSlug = slug("tenant_acme", boxId);
        looseSlug = slug("tenant_acme", looseId);

        admin = adminCookie(acme);
        bravoAdmin = adminCookie(bravo);
        frontDesk = frontDeskCookie(acme, "psz-desk@acme.test");
        frontDeskNoPerm = frontDeskCookie(acme, "psz-desk2@acme.test");
        templateBefore = jdbc.queryForList("SELECT permission, enabled FROM tenant_acme.front_desk_permission_templates");
        jdbc.update("DELETE FROM tenant_acme.front_desk_permission_templates");
        // the template grants the permissions to every front desk user; the no-permission desk is denied by an override
        for (String p : List.of("PRODUCT_VIEW_ADMIN_CATALOG", "STOCK_CONVERSION")) {
            jdbc.update("INSERT INTO tenant_acme.front_desk_permission_templates (permission, enabled, created_at) VALUES (?, true, now())", p);
        }
        long noPermUser = jdbc.queryForObject("SELECT id FROM tenant_acme.users WHERE email = 'psz-desk2@acme.test'", Long.class);
        for (String p : List.of("PRODUCT_VIEW_ADMIN_CATALOG", "STOCK_CONVERSION")) {
            jdbc.update("INSERT INTO tenant_acme.front_desk_user_permission_overrides (user_id, permission, allowed, created_at) VALUES (?, ?, false, now())", noPermUser, p);
        }
    }

    @AfterEach
    void cleanup() {
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("UPDATE " + schema + ".product_families SET base_unit_id = NULL WHERE family_code LIKE 'PSZ-%'");
            jdbc.update("DELETE FROM " + schema + ".products WHERE sku LIKE 'PSZ-%'");
            jdbc.update("DELETE FROM " + schema + ".product_families WHERE family_code LIKE 'PSZ-%'");
        }
        jdbc.update("DELETE FROM tenant_acme.front_desk_user_permission_overrides WHERE user_id IN (SELECT id FROM tenant_acme.users WHERE email LIKE 'psz-desk%')");
        if (templateBefore != null) {
            jdbc.update("DELETE FROM tenant_acme.front_desk_permission_templates");
            templateBefore.forEach(r -> jdbc.update("INSERT INTO tenant_acme.front_desk_permission_templates (permission, enabled, created_at) VALUES (?, ?, now())",
                    r.get("permission"), r.get("enabled")));
            templateBefore = null;
        }
        jdbc.update("DELETE FROM tenant_acme.users WHERE email LIKE 'psz-desk%'");
    }

    // ------------------------------------------------------------------------------------------ fixtures

    private long family(String schema, String code) {
        return jdbc.queryForObject("INSERT INTO " + schema + ".product_families (family_code, name) VALUES (?, ?) RETURNING id", Long.class, code, code);
    }

    private long unit(String schema, String name, String sku, int stock, Long fam, String uom, int factor, boolean base) {
        String slug = sku.toLowerCase() + "-slug";
        return jdbc.queryForObject("INSERT INTO " + schema + ".products (version, name, slug, sku, stock_quantity, sold_count, is_active, is_enlisted, is_featured, "
                + "price_amount, price_currency, created_at, family_id, unit_of_measure_id, conversion_factor, is_base_unit) "
                + "VALUES (0, ?, ?, ?, ?, 0, true, true, true, 10, 'GHS', now(), ?, (SELECT id FROM " + schema + ".units_of_measure WHERE code = ?), ?, ?) RETURNING id",
                Long.class, name, slug, sku, stock, fam, uom, factor, base);
    }

    private String slug(String schema, long id) {
        return jdbc.queryForObject("SELECT slug FROM " + schema + ".products WHERE id = ?", String.class, id);
    }

    private Cookie adminCookie(TestTenant t) {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM " + t.schemaName() + ".users WHERE email = ?", t.adminEmail());
        return new Cookie("accessToken", tenants.runAs(t, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name"))));
    }

    private Cookie frontDeskCookie(TestTenant t, String email) {
        long id = jdbc.queryForObject("INSERT INTO " + t.schemaName() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
        return new Cookie("accessToken", tenants.runAs(t, () -> jwtUtil.generateAccessToken(email, "FRONT_DESK", id, "Desk")));
    }

    private JsonNode get(String path, String tenant, Cookie c, int expectedStatus) throws Exception {
        var b = MockMvcRequestBuilders.get(path).header("X-Tenant-ID", tenant);
        if (c != null) b = b.cookie(c);
        MvcResult r = mvc.perform(b).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + " " + r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return r.getResponse().getContentAsString().isBlank() ? null : body(r);
    }

    private static JsonNode row(JsonNode content, long productId) {
        for (JsonNode n : content) if (n.get("productId").asLong() == productId) return n;
        throw new AssertionError("product " + productId + " not in " + content);
    }

    private void assertPack(JsonNode n, Long fam, Boolean base, Integer factor, String uom) {
        for (String f : PACK_FIELDS) assertThat(n.has(f)).as(f + " present (possibly null) in " + n).isTrue();
        assertThat(n.get("familyId").isNull() ? null : n.get("familyId").asLong()).isEqualTo(fam);
        assertThat(n.get("isBaseUnit").isNull() ? null : n.get("isBaseUnit").asBoolean()).isEqualTo(base);
        assertThat(n.get("conversionFactor").isNull() ? null : n.get("conversionFactor").asInt()).isEqualTo(factor);
        assertThat(n.get("uomCode").isNull() ? null : n.get("uomCode").asText()).isEqualTo(uom);
    }

    private void assertPackFor(JsonNode baseRow, JsonNode boxRow, JsonNode looseRow) {
        assertPack(baseRow, familyId, true, 1, "EA");
        assertPack(boxRow, familyId, false, 6, "BOX");
        assertPack(looseRow, null, null, null, null);
    }

    // ------------------------------------------------------------------------------------------ admin rows

    @Test
    void adminListingCarriesThePackFields() throws Exception {
        for (Cookie c : List.of(admin, frontDesk)) {
            JsonNode content = get("/api/v1/product/admin/listing?size=100", "acme", c, 200).at("/data/content");
            assertPackFor(row(content, baseId), row(content, boxId), row(content, looseId));
            assertThat(row(content, boxId).get("stockQuantity").asInt()).isEqualTo(7); // existing fields unchanged
        }
    }

    @Test
    void adminSearchCarriesThePackFields() throws Exception {
        for (Cookie c : List.of(admin, frontDesk, frontDeskNoPerm)) { // search stays open to every FRONT_DESK by role
            JsonNode content = get("/api/v1/product/admin/search?searchTerm=Psz&size=100", "acme", c, 200).at("/data/content");
            assertThat(content).hasSize(3);
            assertPackFor(row(content, baseId), row(content, boxId), row(content, looseId));
        }
    }

    @Test
    void productDetailsCarryThePackFields() throws Exception {
        for (Cookie c : List.of(admin, frontDesk)) {
            assertPack(get("/api/v1/product/main/" + baseId, "acme", c, 200).get("data"), familyId, true, 1, "EA");
            assertPack(get("/api/v1/product/main/" + boxId, "acme", c, 200).get("data"), familyId, false, 6, "BOX");
            assertPack(get("/api/v1/product/main/" + looseId, "acme", c, 200).get("data"), null, null, null, null);
        }
    }

    @Test
    void aProductWithAFamilyButNoUnitOfMeasureReportsNullUom_andAFamilylessProductIgnoresAStrayUom() throws Exception {
        jdbc.update("UPDATE tenant_acme.products SET unit_of_measure_id = NULL WHERE id = ?", boxId);
        assertPack(get("/api/v1/product/main/" + boxId, "acme", admin, 200).get("data"), familyId, false, 6, null);
        jdbc.update("UPDATE tenant_acme.products SET unit_of_measure_id = (SELECT id FROM tenant_acme.units_of_measure WHERE code = 'EA') WHERE id = ?", looseId);
        assertPack(get("/api/v1/product/main/" + looseId, "acme", admin, 200).get("data"), null, null, null, null);
    }

    @Test
    void createReturnsThePackFieldsOfTheNewFamilyBaseUnit() throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/product/add-product")
                .param("productName", "Pszcreate").param("currency", "GHS").param("sellingPrice", "10").param("costPrice", "5")
                .param("isNewProduct", "true").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(admin)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("familyId").isNumber()).isTrue();
        assertThat(d.get("isBaseUnit").asBoolean()).isTrue();
        assertThat(d.get("conversionFactor").asInt()).isEqualTo(1);
        assertThat(d.get("uomCode").asText()).isEqualTo("EA");
        jdbc.update("UPDATE tenant_acme.product_families SET base_unit_id = NULL WHERE name LIKE 'Pszcreate%'");
        jdbc.update("DELETE FROM tenant_acme.products WHERE name = 'Pszcreate'");
        jdbc.update("DELETE FROM tenant_acme.product_families WHERE name LIKE 'Pszcreate%'");
    }

    // ------------------------------------------------------------------------------------------ variants

    @Test
    void variantsCarryFactorStockAndUom_andAreAdditive() throws Exception {
        for (Cookie c : List.of(admin, frontDesk)) {
            JsonNode data = get("/api/v1/admin/stock-conversions/reverse/" + baseId + "/variants", "acme", c, 200).get("data");
            assertThat(data).hasSize(1);
            JsonNode v = data.get(0);
            assertThat(new TreeSet<String>() {{ v.fieldNames().forEachRemaining(this::add); }})
                    .containsExactlyInAnyOrder("variantId", "variantSku", "variantName", "conversionFactor", "stockQuantity", "uomCode");
            assertThat(v.get("variantId").asLong()).isEqualTo(boxId);
            assertThat(v.get("variantSku").asText()).isEqualTo("PSZ-1-BOX");
            assertThat(v.get("variantName").asText()).isEqualTo("Psz Oil Box");
            assertThat(v.get("conversionFactor").asInt()).isEqualTo(6);
            assertThat(v.get("stockQuantity").asInt()).isEqualTo(7);
            assertThat(v.get("uomCode").asText()).isEqualTo("BOX");
        }
    }

    @Test
    void variantsOfABulkUnitOrAFamilylessProductAreStill400() throws Exception {
        get("/api/v1/admin/stock-conversions/reverse/" + boxId + "/variants", "acme", admin, 400);
        // a family-less product is a base unit by default but has no family
        get("/api/v1/admin/stock-conversions/reverse/" + looseId + "/variants", "acme", admin, 400);
    }

    // ------------------------------------------------------------------------------------------ access and tenancy

    @Test
    void accessRulesAreUnchanged() throws Exception {
        for (String p : List.of("/api/v1/product/admin/listing", "/api/v1/product/main/" + baseId,
                "/api/v1/admin/stock-conversions/reverse/" + baseId + "/variants")) {
            get(p, "acme", null, 401);
            get(p, "acme", frontDeskNoPerm, 403); // FRONT_DESK without the permission
            get(p, "acme", bravoAdmin, 401);      // another tenant's token is anonymous here
        }
        get("/api/v1/product/admin/search?searchTerm=Psz", "acme", null, 401);
        get("/api/v1/product/admin/search?searchTerm=Psz", "acme", bravoAdmin, 401);
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        // acme's admin sees none of bravo's rows or families
        JsonNode acmeRows = get("/api/v1/product/admin/listing?size=100", "acme", admin, 200).at("/data/content");
        for (JsonNode n : acmeRows) assertThat(n.get("productName").asText()).doesNotContain("Bravo"); // ids are per schema, names are not
        // bravo's admin reads bravo's own pack data, and cannot read acme's product by id
        assertPack(get("/api/v1/product/main/" + bravoBoxId, "bravo", bravoAdmin, 200).get("data"), bravoFamilyId, false, 24, "BOX");
        JsonNode bravoSearch = get("/api/v1/product/admin/search?searchTerm=Psz&size=100", "bravo", bravoAdmin, 200).at("/data/content");
        assertThat(bravoSearch).hasSize(2);
        assertThat(row(bravoSearch, bravoBoxId).get("conversionFactor").asInt()).isEqualTo(24);
        // (ids are per schema, so a coincidence of ids proves nothing: the rows below are told apart by their SKU/factor)
        JsonNode bravoBoxRow = get("/api/v1/product/main/" + bravoBoxId, "bravo", bravoAdmin, 200).get("data");
        assertThat(bravoBoxRow.get("stockKeepingUnit").asText()).isEqualTo("PSZ-9-BOX");
        get("/api/v1/admin/stock-conversions/reverse/999999999/variants", "bravo", bravoAdmin, 404);
        // the variants of a bravo base unit list only bravo's box
        long bravoBase = jdbc.queryForObject("SELECT id FROM tenant_bravo.products WHERE sku = 'PSZ-9-EA'", Long.class);
        JsonNode v = get("/api/v1/admin/stock-conversions/reverse/" + bravoBase + "/variants", "bravo", bravoAdmin, 200).get("data");
        assertThat(v).hasSize(1);
        assertThat(v.get(0).get("conversionFactor").asInt()).isEqualTo(24);
    }

    // ------------------------------------------------------------------------------------------ public responses unchanged

    private static List<String> keys(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void publicResponsesKeepTheirExactShapeAndLeakNoPackFields() throws Exception {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        rows.put("listing", get("/api/v1/product/listing?size=100", "acme", null, 200).at("/data/content"));
        rows.put("search", get("/api/v1/product/search?searchTerm=Psz&size=100", "acme", null, 200).at("/data/content"));
        rows.put("featured", get("/api/v1/product/featured", "acme", null, 200).get("data"));
        rows.put("by-ids", get("/api/v1/product/by-ids?ids=" + baseId + "," + boxId + "," + looseId, "acme", null, 200).get("data"));
        for (var e : rows.entrySet()) {
            assertThat(e.getValue().size()).as(e.getKey()).isGreaterThanOrEqualTo(1);
            for (JsonNode n : e.getValue()) {
                assertThat(keys(n)).as(e.getKey() + " row keys").containsExactlyInAnyOrderElementsOf(PUBLIC_ROW_KEYS);
            }
        }
        for (String slug : List.of(baseSlug, boxSlug, looseSlug)) {
            JsonNode page = get("/api/v1/product/" + slug, "acme", null, 200).get("data");
            assertThat(keys(page)).as("details page " + slug).containsExactlyInAnyOrderElementsOf(PUBLIC_PAGE_KEYS);
        }
        // a customer-agnostic raw check: none of the new names appear anywhere in the public bodies
        for (String p : List.of("/api/v1/product/listing?size=100", "/api/v1/product/featured", "/api/v1/product/" + boxSlug)) {
            String raw = mvc.perform(MockMvcRequestBuilders.get(p).header("X-Tenant-ID", "acme")).andReturn().getResponse().getContentAsString();
            for (String f : List.of("familyId", "isBaseUnit", "conversionFactor", "uomCode")) assertThat(raw).as(p).doesNotContain(f);
        }
        // the admin cookie does not change the public routes either
        JsonNode asAdmin = get("/api/v1/product/listing?size=100", "acme", admin, 200).at("/data/content");
        for (JsonNode n : asAdmin) assertThat(keys(n)).containsExactlyInAnyOrderElementsOf(PUBLIC_ROW_KEYS);
    }
}

package com.example.perfume_budget.catalog;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * ORD-02c (ORD-02b review low 2, PO decision 2026-11-18): a new product family gets {@code <prefix>-<3 random digits>}
 * (10 draws), then {@code <prefix>-<4 random digits>} (10 draws), then 409 {@code FAMILY_CODE_UNAVAILABLE}; a code
 * whose base SKU {@code <code>-EA} is already used is skipped; a concurrent unique violation on the family code or the
 * base SKU (check-then-insert race) is retried once in a fresh transaction. Real app + Postgres.
 *
 * <p>All rows use the prefix {@code QZV} (product name "Qzv", no brand, no size) and are removed after each test.
 */
class FamilyCodeAllocationIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final String UNAVAILABLE_MESSAGE = "Could not allocate a product code for this name/brand; try again or change the name";

    @BeforeEach
    @AfterEach
    void dropQzvRows() {
        jdbc.execute("DROP TRIGGER IF EXISTS ord02c_race ON tenant_acme.product_families");
        jdbc.execute("DROP TRIGGER IF EXISTS ord02c_race ON tenant_acme.products");
        jdbc.execute("DROP FUNCTION IF EXISTS tenant_acme.ord02c_race()");
        jdbc.execute("DROP SEQUENCE IF EXISTS tenant_acme.ord02c_race_seq");
        jdbc.update("UPDATE tenant_acme.product_families SET base_unit_id = NULL WHERE family_code LIKE 'QZV-%'");
        jdbc.update("DELETE FROM tenant_acme.products WHERE sku LIKE 'QZV-%' "
                + "OR family_id IN (SELECT id FROM tenant_acme.product_families WHERE family_code LIKE 'QZV-%')");
        jdbc.update("DELETE FROM tenant_acme.product_families WHERE family_code LIKE 'QZV-%'");
    }

    private MvcResult create() throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        return mvc.perform(multipart("/api/v1/product/add-product")
                .param("productName", "Qzv").param("currency", "GHS").param("sellingPrice", "100.00").param("costPrice", "60.00")
                .param("isNewProduct", "true").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }

    /** "200 <familyCode>", "409 FAMILY_CODE_UNAVAILABLE <description>" or the raw status + body for anything else. */
    private String outcome(MvcResult r) throws Exception {
        int status = r.getResponse().getStatus();
        String raw = r.getResponse().getContentAsString();
        if (status == 200) {
            String sku = body(r).at("/data/stockKeepingUnit").asText();
            return "200 " + sku.substring(0, sku.length() - "-EA".length());
        }
        JsonNode b = raw.isBlank() ? null : body(r);
        if (status == 409 && b != null && "FAMILY_CODE_UNAVAILABLE".equals(b.at("/data/code").asText())) {
            return "409 FAMILY_CODE_UNAVAILABLE " + b.at("/description").asText();
        }
        return status + " " + raw;
    }

    private List<String> concurrentCreates(int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<String> task = () -> {
                    go.await();
                    return outcome(create());
                };
                fs.add(pool.submit(task));
            }
            go.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) out.add(f.get(120, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Families 'QZV-<digits>' named 'filler' for every number in [from, to] except the given ones. */
    private void fillFamilies(int digits, int from, int to, int... except) {
        StringBuilder skip = new StringBuilder("-1");
        for (int e : except) skip.append(',').append(e);
        jdbc.update("INSERT INTO tenant_acme.product_families (family_code, name) SELECT 'QZV-' || lpad(g::text, ?, '0'), 'filler' "
                + "FROM generate_series(?, ?) g WHERE g NOT IN (" + skip + ")", digits, from, to);
    }

    private int newFamilies() {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.product_families WHERE family_code LIKE 'QZV-%' AND name <> 'filler'", Integer.class);
    }

    private int qzvProducts() {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products p JOIN tenant_acme.product_families f ON f.id = p.family_id "
                + "WHERE f.family_code LIKE 'QZV-%'", Integer.class);
    }

    /** Every new family has exactly its base product (SKU <code>-EA, set as base unit): no orphan family, no orphan product. */
    private void assertNoOrphans(long ok) {
        assertThat(newFamilies()).as("new families").isEqualTo(ok);
        assertThat(qzvProducts()).as("products in QZV families").isEqualTo(ok);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.product_families f JOIN tenant_acme.products p ON p.id = f.base_unit_id "
                + "WHERE f.family_code LIKE 'QZV-%' AND f.name <> 'filler' AND p.family_id = f.id AND p.sku = f.family_code || '-EA'", Integer.class))
                .as("families wired to their own base unit").isEqualTo((int) ok);
    }

    @Test
    void concurrentCreatesOnANearlyFullThreeDigitPrefixAllSucceedThroughTheFourDigitFallback() throws Exception {
        fillFamilies(3, 0, 999, 101, 202, 303, 404, 505); // 995 of 1000 three-digit codes taken
        List<String> all = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            List<String> out = concurrentCreates(12);
            all.addAll(out);
            assertThat(out).as("round " + round).allMatch(o -> o.startsWith("200 "));
        }
        List<String> codes = all.stream().map(o -> o.substring(4)).toList();
        assertThat(codes).allMatch(c -> c.matches("QZV-\\d{3}|QZV-\\d{4}"));
        assertThat(new HashSet<>(codes)).as("distinct codes").hasSize(36);
        assertThat(codes.stream().filter(c -> c.matches("QZV-\\d{4}")).count()).as("most went to the 4-digit space").isGreaterThan(20);
        assertNoOrphans(36);
        // existing codes are never changed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.product_families WHERE name = 'filler'", Integer.class)).isEqualTo(995);
    }

    @Test
    void concurrentCreatesOnAnExhaustedPrefixAnswerOnly200OrFamilyCodeUnavailable() throws Exception {
        fillFamilies(3, 0, 999);
        fillFamilies(4, 0, 9999, 4242, 7777); // two free 4-digit codes left
        List<String> all = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            all.addAll(concurrentCreates(12));
        }
        System.out.println("ORD02C exhausted prefix outcomes " + all);
        assertThat(all).allMatch(o -> o.equals("200 QZV-4242") || o.equals("200 QZV-7777")
                || o.equals("409 FAMILY_CODE_UNAVAILABLE " + UNAVAILABLE_MESSAGE));
        long ok = all.stream().filter(o -> o.startsWith("200 ")).count();
        assertThat(all.stream().filter(o -> o.startsWith("409 FAMILY_CODE_UNAVAILABLE")).count()).isGreaterThanOrEqualTo(22);
        assertThat(all.stream().filter(o -> o.startsWith("200 ")).distinct().count()).isEqualTo(ok);
        assertNoOrphans(ok);
    }

    @Test
    void aCodeWhoseBaseSkuIsAlreadyUsedByACustomisedProductIsSkipped() throws Exception {
        // products of no family that hold every SKU QZV-000-EA .. QZV-999-EA (legacy / customised SKUs)
        jdbc.update("INSERT INTO tenant_acme.products (version, name, slug, sku, stock_quantity, sold_count, is_active, is_enlisted, "
                + "price_amount, price_currency, created_at) SELECT 0, 'filler', 'qzv-filler-' || g, 'QZV-' || lpad(g::text, 3, '0') || '-EA', "
                + "0, 0, false, false, 1, 'GHS', now() FROM generate_series(0, 999) g");
        String first = outcome(create());
        String second = outcome(create());
        assertThat(first).matches("200 QZV-\\d{4}");
        assertThat(second).matches("200 QZV-\\d{4}");
        assertNoOrphans(2);
    }

    /**
     * Simulates the check-then-insert race deterministically: a trigger makes the first {@code n} QZV inserts into
     * {@code table} fail exactly like a concurrent insert of the same key (SQLState 23505 on {@code constraint}); a
     * sequence counts the insert attempts (nextval is not rolled back with the failed attempt).
     */
    private void raceOn(String table, String constraint, int n) {
        jdbc.execute("CREATE SEQUENCE tenant_acme.ord02c_race_seq");
        String keyColumn = table.equals("products") ? "sku" : "family_code";
        jdbc.execute("CREATE FUNCTION tenant_acme.ord02c_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW." + keyColumn + " LIKE 'QZV-%' AND nextval('tenant_acme.ord02c_race_seq') <= " + n + " THEN "
                + "RAISE EXCEPTION USING ERRCODE = 'unique_violation', CONSTRAINT = '" + constraint + "', "
                + "MESSAGE = 'duplicate key value violates unique constraint \"" + constraint + "\"'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER ord02c_race BEFORE INSERT ON tenant_acme." + table
                + " FOR EACH ROW EXECUTE FUNCTION tenant_acme.ord02c_race()");
    }

    private long raceAttempts() {
        return jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM tenant_acme.ord02c_race_seq", Long.class);
    }

    @Test
    void aConcurrentFamilyCodeClashIsRetriedOnceInAFreshAttempt() throws Exception {
        raceOn("product_families", "uc_product_families_family_code", 1);
        assertThat(outcome(create())).matches("200 QZV-\\d{3}");
        assertThat(raceAttempts()).as("one failed insert, one retried insert").isEqualTo(2);
        assertNoOrphans(1);
    }

    @Test
    void aConcurrentBaseSkuClashIsRetriedOnceInAFreshAttempt() throws Exception {
        raceOn("products", "uc_products_sku", 1);
        assertThat(outcome(create())).matches("200 QZV-\\d{3}");
        assertThat(raceAttempts()).isEqualTo(2);
        assertNoOrphans(1);
    }

    @Test
    void aSecondConcurrentClashAnswersFamilyCodeUnavailableAndLeavesNoRows() throws Exception {
        raceOn("product_families", "uc_product_families_family_code", 2);
        assertThat(outcome(create())).isEqualTo("409 FAMILY_CODE_UNAVAILABLE " + UNAVAILABLE_MESSAGE);
        assertThat(raceAttempts()).as("retried exactly once").isEqualTo(2);
        assertNoOrphans(0);
    }

    @Test
    void anUnrelatedConstraintViolationIsNotRetriedAndKeepsTheGeneric409() throws Exception {
        raceOn("products", "uc_products_something_else", 5);
        MvcResult r = create();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).at("/description").asText()).isEqualTo("A constraint violation occurred");
        assertThat(body(r).at("/data/code").isMissingNode() || body(r).at("/data/code").isNull()).isTrue();
        assertThat(raceAttempts()).as("not retried").isEqualTo(1);
        assertNoOrphans(0);
    }

    /** ORD-02d: the loser of two identical concurrent variant adds gets the documented 400, not the generic 409. */
    @Test
    void aConcurrentIdenticalVariantAddLosesWithTheDocumented400_notTheGeneric409() throws Exception {
        MvcResult base = create();
        assertThat(base.getResponse().getStatus()).isEqualTo(200);
        long baseId = body(base).at("/data/productId").asLong();
        long family = jdbc.queryForObject("SELECT family_id FROM tenant_acme.products WHERE id = ?", Long.class, baseId);
        // the insert of '<code>-BOX' fails like a concurrent insert of the same SKU (23505 on uc_products_sku) would
        raceOn("products", "uc_products_sku", 1);
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        MvcResult r = mvc.perform(multipart("/api/v1/product/add-product")
                .param("productName", "Qzv").param("currency", "GHS").param("sellingPrice", "500.00").param("costPrice", "0")
                .param("isNewProduct", "false").param("familyId", String.valueOf(family)).param("uomCode", "BOX")
                .param("conversionFactor", "6").param("isActive", "false").param("isEnlisted", "false")
                .header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(r).at("/description").asText()).isEqualTo("Variant with UOM BOX already exists for this family.");
        assertThat(raceAttempts()).as("not retried").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.products WHERE family_id = ?", Integer.class, family))
                .as("only the base unit").isEqualTo(1);
    }
}

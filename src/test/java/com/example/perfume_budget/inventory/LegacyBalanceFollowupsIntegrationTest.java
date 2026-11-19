package com.example.perfume_budget.inventory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * INV-01b (INV-01 review lows): L1 a negative legacy stock_quantity is deliberately not bootstrapped (pinned, WARN logged,
 * R07); L2 the first receipt of a product with a zero or negative legacy balance is OPENING_STOCK, a positive legacy
 * balance keeps its LEGACY_BALANCE layer and the receipt is a PURCHASE; L3 the read-only report of legacy balances that a
 * pre-INV-01 receipt overwrote, and the opt-in repair. Real app + Postgres.
 */
class LegacyBalanceFollowupsIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Cookie admin;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() throws Exception {
        admin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        serviceLogger = (Logger) LoggerFactory.getLogger(com.example.perfume_budget.service.InventoryManagementServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        serviceLogger.detachAppender(logs);
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder b, Object content) throws Exception {
        b.header("X-Tenant-ID", acme.slug()).cookie(admin);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private void receive(long p, int qty) throws Exception {
        MvcResult r = asAdmin(post("/api/v1/admin/inventory/receipts"), Map.of("productId", p, "quantity", qty,
                "unitCost", "40.00", "unitSellingPrice", "50.00", "reference", "inv01b"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    private long legacy(String name, int stock) {
        long p = product(acme, name, "100.00", stock);
        jdbc.update("UPDATE tenant_acme.products SET cost_price_amount = 60, cost_price_currency = 'GHS' WHERE id = ?", p);
        return p;
    }

    private int stock(long p) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    private List<String> layers(long p) {
        return jdbc.queryForList("SELECT source_type || ' ' || received_quantity || '/' || remaining_quantity "
                + "FROM tenant_acme.inventory_layers WHERE product_id = ? ORDER BY received_at, id", String.class, p);
    }

    private List<String> movements(long p) {
        return jdbc.queryForList("SELECT movement_type || ' ' || quantity FROM tenant_acme.inventory_movements WHERE product_id = ? ORDER BY id",
                String.class, p);
    }

    private boolean warned(long p) {
        return logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("Product " + p + " has a negative legacy stock_quantity"));
    }

    // ---------------------------------------------------------------- L1 + L2

    @Test
    void negativeLegacyBalance_isNotBootstrapped_warns_andTheFirstReceiptIsOpeningStock() throws Exception {
        long p = legacy("Qinv Negative", -3);
        receive(p, 5);

        assertThat(layers(p)).as("no negative layer, no LEGACY_BALANCE: only the receipt").containsExactly("OPENING_STOCK 5/5");
        assertThat(movements(p)).containsExactly("RECEIPT 5");
        assertThat(stock(p)).as("the negative balance is replaced by the layer sum").isEqualTo(5);
        assertThat(warned(p)).as("WARN logged").isTrue();
    }

    @Test
    void negativeLegacyBalance_isLeftAloneByTheAdminReads() throws Exception {
        long p = legacy("Qinv Negative Read", -2);
        MvcResult r = asAdmin(get("/api/v1/admin/inventory/products/" + p + "/summary"), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(layers(p)).isEmpty();
        assertThat(movements(p)).isEmpty();
        assertThat(stock(p)).isEqualTo(-2);
    }

    @Test
    void zeroLegacyBalance_firstReceiptIsOpeningStock_noLegacyLayer() throws Exception {
        long p = legacy("Qinv Zero", 0);
        receive(p, 4);
        assertThat(layers(p)).containsExactly("OPENING_STOCK 4/4");
        assertThat(movements(p)).containsExactly("RECEIPT 4");
        assertThat(warned(p)).isFalse();
    }

    @Test
    void positiveLegacyBalance_isKeptAsALegacyLayer_andTheReceiptIsAPurchase() throws Exception {
        long p = legacy("Qinv Positive", 4);
        receive(p, 3);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 4/4", "PURCHASE 3/3");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 4", "RECEIPT 3");
        assertThat(stock(p)).isEqualTo(7);
        assertThat(warned(p)).isFalse();
    }

    // ---------------------------------------------------------------- L3

    private long defaultLocation() {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.storage_locations WHERE is_default_receiving", Long.class);
    }

    /** What a pre-INV-01 receipt left behind: one PURCHASE layer worth {@code stock}, a ledger that still holds {@code onHand}. */
    private long overwritten(String name, int stock, int onHand) {
        long p = legacy(name, stock);
        LocalDateTime t = LocalDateTime.now().minusDays(3);
        long layer = jdbc.queryForObject("INSERT INTO tenant_acme.inventory_layers (product_id, source_type, source_reference, received_quantity, "
                + "remaining_quantity, unit_cost, unit_selling_price, currency_code, received_at, created_by, created_at) "
                + "VALUES (?, 'PURCHASE', 'old', ?, ?, 40, 50, 'GHS', ?, 'x', now()) RETURNING id", Long.class, p, stock, stock, t);
        jdbc.update("INSERT INTO tenant_acme.inventory_movements (layer_id, product_id, movement_type, reference_type, reference_id, "
                + "reference_line_key, quantity, unit_cost, unit_selling_price, currency_code, created_at) "
                + "VALUES (?, ?, 'RECEIPT', 'RECEIPT', 'old', 'receipt:' || ?, ?, 40, 50, 'GHS', now())", layer, p, p, stock);
        ledger(p, onHand);
        return p;
    }

    private void ledger(long p, int onHand) {
        jdbc.update("INSERT INTO tenant_acme.location_stock (product_id, location_id, quantity_on_hand, version, created_at) "
                + "VALUES (?, ?, ?, 0, now())", p, defaultLocation(), onHand);
    }

    private JsonNode report() throws Exception {
        MvcResult r = asAdmin(get("/api/v1/admin/inventory/legacy-balance-report"), null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private JsonNode line(JsonNode report, long p) {
        for (JsonNode n : report.get("products")) {
            if (n.get("productId").asLong() == p) return n;
        }
        return null;
    }

    @Test
    void report_listsExactlyTheOverwrittenBalances_readOnly() throws Exception {
        long lost = overwritten("Qinv Lost", 3, 8);                  // 5 units gone
        long healthy = overwritten("Qinv Healthy", 4, 4);            // ledger agrees
        long opening = overwritten("Qinv Opening", 3, 8);            // has an OPENING_STOCK layer: a real opening, not legacy
        jdbc.update("INSERT INTO tenant_acme.inventory_layers (product_id, source_type, received_quantity, remaining_quantity, unit_cost, "
                + "unit_selling_price, currency_code, received_at, created_at) VALUES (?, 'OPENING_STOCK', 0, 0, 1, 1, 'GHS', now(), now())", opening);
        long bootstrapped = overwritten("Qinv Bootstrapped", 3, 8);  // already has a LEGACY_BOOTSTRAP movement
        jdbc.update("INSERT INTO tenant_acme.inventory_movements (product_id, movement_type, reference_type, reference_id, quantity, unit_cost, "
                + "unit_selling_price, currency_code, created_at) VALUES (?, 'LEGACY_BOOTSTRAP', 'LEGACY', 'LEGACY-x', 0, 1, 1, 'GHS', now())", bootstrapped);
        long reserved = overwritten("Qinv Reserved", 2, 5);          // 3 units reserved: ledger = stock + reserved, balanced
        long layerId = jdbc.queryForObject("SELECT id FROM tenant_acme.inventory_layers WHERE product_id = ?", Long.class, reserved);
        jdbc.update("INSERT INTO tenant_acme.inventory_allocations (layer_id, product_id, reference_type, reference_id, reference_line_key, quantity, "
                + "unit_cost, unit_selling_price, currency_code, status, created_at) VALUES (?, ?, 'ORDER', 'PB-X', 'k', 3, 40, 50, 'GHS', 'RESERVED', now())",
                layerId, reserved);
        long short_ = overwritten("Qinv Short", 3, 1);               // ledger below stock: reported, not repairable
        long noPurchase = legacy("Qinv Legacy Untouched", 6);        // still a legacy product (no layer)
        ledger(noPurchase, 9);

        int layersBefore = count(acme, "inventory_layers");
        int movementsBefore = count(acme, "inventory_movements");
        JsonNode r = report();

        assertThat(line(r, lost).get("lostUnits").asInt()).isEqualTo(5);
        assertThat(line(r, lost).get("repairable").asBoolean()).isTrue();
        assertThat(line(r, lost).get("stockQuantity").asInt()).isEqualTo(3);
        assertThat(line(r, lost).get("locationOnHand").asInt()).isEqualTo(8);
        assertThat(line(r, lost).get("reservedQuantity").asInt()).isZero();
        for (long other : new long[]{healthy, opening, bootstrapped, reserved, noPurchase}) {
            assertThat(line(r, other)).as("product " + other + " is not reported").isNull();
        }
        assertThat(line(r, short_).get("lostUnits").asInt()).isEqualTo(-2);
        assertThat(line(r, short_).get("repairable").asBoolean()).isFalse();
        assertThat(r.get("totalLostUnits").asInt()).as("only repairable lines count").isEqualTo(5);
        assertThat(r.get("truncated").asBoolean()).isFalse();
        // read only
        assertThat(count(acme, "inventory_layers")).isEqualTo(layersBefore);
        assertThat(count(acme, "inventory_movements")).isEqualTo(movementsBefore);
        assertThat(stock(lost)).isEqualTo(3);
    }

    @Test
    void repair_restoresTheLostUnitsAsABackdatedLegacyLayer_once_andOnlyTheRequestedProducts() throws Exception {
        long a = overwritten("Qinv Repair A", 3, 8);
        long b = overwritten("Qinv Repair B", 2, 6);
        MvcResult r = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(a)));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode data = body(r).get("data");
        assertThat(data.at("/results/0/outcome").asText()).isEqualTo("REPAIRED");
        assertThat(data.at("/results/0/restoredUnits").asInt()).isEqualTo(5);
        assertThat(data.get("restoredUnits").asInt()).isEqualTo(5);

        assertThat(layers(a)).as("legacy layer sorts first (FIFO)").containsExactly("LEGACY_BALANCE 5/5", "PURCHASE 3/3");
        assertThat(movements(a)).containsExactly("RECEIPT 3", "LEGACY_BOOTSTRAP 5");
        assertThat(stock(a)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT (SELECT min(received_at) FROM tenant_acme.inventory_layers WHERE product_id = ? AND source_type = 'LEGACY_BALANCE') "
                + "< (SELECT min(received_at) FROM tenant_acme.inventory_layers WHERE product_id = ? AND source_type = 'PURCHASE')", Boolean.class, a, a)).isTrue();
        assertThat(jdbc.queryForObject("SELECT sum(quantity_on_hand) FROM tenant_acme.location_stock WHERE product_id = ?", Integer.class, a))
                .as("the location ledger is not touched").isEqualTo(8);
        // the other product was not requested: untouched
        assertThat(layers(b)).containsExactly("PURCHASE 2/2");
        assertThat(stock(b)).isEqualTo(2);

        // once: the product leaves the report and a second run does nothing
        assertThat(line(report(), a)).isNull();
        assertThat(line(report(), b)).isNotNull();
        MvcResult again = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(a)));
        assertThat(body(again).at("/data/results/0/outcome").asText()).isEqualTo("SKIPPED");
        assertThat(body(again).at("/data/results/0/reason").asText()).isEqualTo("NOT_AFFECTED");
        assertThat(layers(a)).hasSize(2);
        assertThat(stock(a)).isEqualTo(8);
    }

    /**
     * FIX-C review low (mutant M13): the repair layer's unit cost is the oldest layer's COST and its selling price is the
     * oldest layer's SELLING price; the fixture uses three different numbers (cost 37.25, layer price 91.50, product
     * list price 100.00) so a layer that copied the selling price into the cost, the cost into the price, or took the
     * product's current price, is caught on the layer and on its LEGACY_BOOTSTRAP movement.
     */
    @Test
    void repair_copiesTheOldestLayersCostAndSellingPrice_notOneForTheOther_andNotTheProductsPrice() throws Exception {
        long p = legacy("Qinv Repair Prices", 3);
        LocalDateTime t = LocalDateTime.now().minusDays(3);
        long layer = jdbc.queryForObject("INSERT INTO tenant_acme.inventory_layers (product_id, source_type, source_reference, received_quantity, "
                + "remaining_quantity, unit_cost, unit_selling_price, currency_code, received_at, created_by, created_at) "
                + "VALUES (?, 'PURCHASE', 'old', 3, 3, 37.25, 91.50, 'GHS', ?, 'x', now()) RETURNING id", Long.class, p, t);
        jdbc.update("INSERT INTO tenant_acme.inventory_movements (layer_id, product_id, movement_type, reference_type, reference_id, "
                + "reference_line_key, quantity, unit_cost, unit_selling_price, currency_code, created_at) "
                + "VALUES (?, ?, 'RECEIPT', 'RECEIPT', 'old', 'receipt:' || ?, 3, 37.25, 91.50, 'GHS', now())", layer, p, p);
        ledger(p, 8);

        MvcResult r = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(p)));
        assertThat(body(r).at("/data/results/0/outcome").asText()).as(r.getResponse().getContentAsString()).isEqualTo("REPAIRED");

        Map<String, Object> legacyLayer = jdbc.queryForMap("SELECT unit_cost, unit_selling_price, currency_code FROM tenant_acme.inventory_layers "
                + "WHERE product_id = ? AND source_type = 'LEGACY_BALANCE'", p);
        assertThat((java.math.BigDecimal) legacyLayer.get("unit_cost")).isEqualByComparingTo("37.25");
        assertThat((java.math.BigDecimal) legacyLayer.get("unit_selling_price")).isEqualByComparingTo("91.50");
        assertThat(legacyLayer.get("currency_code")).isEqualTo("GHS");
        Map<String, Object> movement = jdbc.queryForMap("SELECT unit_cost, unit_selling_price FROM tenant_acme.inventory_movements "
                + "WHERE product_id = ? AND movement_type = 'LEGACY_BOOTSTRAP'", p);
        assertThat((java.math.BigDecimal) movement.get("unit_cost")).isEqualByComparingTo("37.25");
        assertThat((java.math.BigDecimal) movement.get("unit_selling_price")).isEqualByComparingTo("91.50");
    }

    /** FIX-C review low: the front desk (a staff role below ADMIN) gets 403 on both legacy-balance routes, nothing written. */
    @Test
    void frontDesk_is403_onTheReportAndOnTheRepair_andNothingIsWritten() throws Exception {
        long p = overwritten("Qinv FrontDesk", 3, 8);
        Long id = jdbc.queryForObject("INSERT INTO tenant_acme.users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', 'inv01b-desk@acme.test', 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class);
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken("inv01b-desk@acme.test", "FRONT_DESK", id, "Desk"));
        Cookie desk = new Cookie("accessToken", token);
        int layersBefore = count(acme, "inventory_layers");
        int movementsBefore = count(acme, "inventory_movements");

        MvcResult report = mvc.perform(get("/api/v1/admin/inventory/legacy-balance-report").header("X-Tenant-ID", "acme").cookie(desk)).andReturn();
        MvcResult repair = mvc.perform(post("/api/v1/admin/inventory/legacy-balance-repair").header("X-Tenant-ID", "acme").cookie(desk)
                .contentType("application/json").content("{\"productIds\":[" + p + "]}")).andReturn();

        assertThat(report.getResponse().getStatus()).as(report.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(repair.getResponse().getStatus()).as(repair.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(count(acme, "inventory_layers")).isEqualTo(layersBefore);
        assertThat(count(acme, "inventory_movements")).isEqualTo(movementsBefore);
        assertThat(layers(p)).containsExactly("PURCHASE 3/3");
        // and the admin still can (the 403 is about the role, not the route)
        assertThat(asAdmin(get("/api/v1/admin/inventory/legacy-balance-report"), null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void repair_countsOutstandingReservationsOutOfTheLostUnits() throws Exception {
        long p = overwritten("Qinv Repair Reserved", 2, 9);          // 9 on the shelf = 2 in stock + 3 reserved + 4 lost
        long layerId = jdbc.queryForObject("SELECT id FROM tenant_acme.inventory_layers WHERE product_id = ?", Long.class, p);
        jdbc.update("INSERT INTO tenant_acme.inventory_allocations (layer_id, product_id, reference_type, reference_id, reference_line_key, quantity, "
                + "unit_cost, unit_selling_price, currency_code, status, created_at) VALUES (?, ?, 'ORDER', 'PB-Y', 'k', 3, 40, 50, 'GHS', 'RESERVED', now())",
                layerId, p);
        assertThat(line(report(), p).get("lostUnits").asInt()).isEqualTo(4);
        MvcResult r = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(p)));
        assertThat(body(r).at("/data/results/0/restoredUnits").asInt()).isEqualTo(4);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 4/4", "PURCHASE 2/2");
        assertThat(stock(p)).isEqualTo(6);
    }

    @Test
    void repair_skipsWhatIsNotRepairable_andUnknownIds() throws Exception {
        long short_ = overwritten("Qinv Repair Short", 3, 1);
        long healthy = overwritten("Qinv Repair Healthy", 4, 4);
        MvcResult r = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"),
                Map.of("productIds", List.of(short_, healthy, 987654321L)));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode results = body(r).at("/data/results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("reason").asText()).isEqualTo("NOTHING_TO_RESTORE");   // lowest id first
        assertThat(results.get(1).get("reason").asText()).isEqualTo("NOT_AFFECTED");
        assertThat(results.get(2).get("reason").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(layers(short_)).containsExactly("PURCHASE 3/3");
        assertThat(layers(healthy)).containsExactly("PURCHASE 4/4");
    }

    @Test
    void repair_requiresAnExplicitListAndAnAdmin() throws Exception {
        assertThat(asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of()))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(0))).getResponse().getStatus()).isEqualTo(400);
        // anonymous and a customer are refused on both routes
        assertThat(mvc.perform(get("/api/v1/admin/inventory/legacy-balance-report").header("X-Tenant-ID", "acme")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        Customer c = customer(acme, "inv01b-customer@acme.test");
        assertThat(mvc.perform(get("/api/v1/admin/inventory/legacy-balance-report").header("X-Tenant-ID", "acme")
                .cookie(new Cookie("accessToken", c.token()))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/admin/inventory/legacy-balance-repair").header("X-Tenant-ID", "acme")
                .cookie(new Cookie("accessToken", c.token())).contentType("application/json").content("{\"productIds\":[1]}")).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void report_isPerTenant_anotherTenantsOverwrittenBalanceNeverShows() throws Exception {
        long foreign = product(bravo, "Qinv Bravo Lost", "10.00", 3);
        jdbc.update("INSERT INTO tenant_bravo.inventory_layers (product_id, source_type, received_quantity, remaining_quantity, unit_cost, "
                + "unit_selling_price, currency_code, received_at, created_at) VALUES (?, 'PURCHASE', 3, 3, 1, 1, 'GHS', now(), now())", foreign);
        Long bravoLocation = jdbc.queryForObject("SELECT id FROM tenant_bravo.storage_locations WHERE is_default_receiving", Long.class);
        jdbc.update("INSERT INTO tenant_bravo.location_stock (product_id, location_id, quantity_on_hand, version, created_at) VALUES (?, ?, 9, 0, now())",
                foreign, bravoLocation);
        assertThat(line(report(), foreign)).isNull();
        MvcResult r = asAdmin(post("/api/v1/admin/inventory/legacy-balance-repair"), Map.of("productIds", List.of(foreign)));
        assertThat(body(r).at("/data/results/0/outcome").asText()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_bravo.inventory_layers WHERE product_id = ?", Integer.class, foreign)).isEqualTo(1);
    }
}

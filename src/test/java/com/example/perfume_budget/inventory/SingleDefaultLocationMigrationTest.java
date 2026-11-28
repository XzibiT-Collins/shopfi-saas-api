package com.example.perfume_budget.inventory;

import com.example.perfume_budget.support.PostgresAvailableCondition;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLAN-07 AC1: the REAL tenant migrations (classpath:db/migrations/tenant) run into scratch schemas. A schema is migrated
 * to V50 (an existing tenant: V5 seeded 'Shop Floor' and 'Store Room 1'), given data, then V51 runs. The seeded store room
 * goes only while nothing references it; 'Shop Floor' and its three default roles always stay; a re-run changes nothing.
 */
@ExtendWith(PostgresAvailableCondition.class)
class SingleDefaultLocationMigrationTest {

    private static final String SEED_ROW = "Shop Floor|SHOP_FLOOR|t|t|t|t";

    private final List<String> scratch = new ArrayList<>();

    private String newSchema() {
        String s = "plan07_mig_" + System.nanoTime();
        scratch.add(s);
        return s;
    }

    private static FluentConfiguration config(String schema) {
        TestDatabaseGuard.requireDisposable(PostgresTestDatabase.URL);
        return Flyway.configure().dataSource(PostgresTestDatabase.URL, PostgresTestDatabase.USER, PostgresTestDatabase.PASSWORD)
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migrations/tenant");
    }

    private static Flyway flyway(String schema, String target) {
        FluentConfiguration cfg = config(schema);
        return (target == null ? cfg : cfg.target(target)).load();
    }

    @AfterEach
    void dropScratch() throws SQLException {
        try (Connection c = PostgresTestDatabase.connect(); Statement st = c.createStatement()) {
            for (String s : scratch) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
    }

    private static void sql(String schema, String sql) throws SQLException {
        try (Connection c = PostgresTestDatabase.connect(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + schema);
            st.execute(sql);
        }
    }

    private static List<String> rows(String schema, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = PostgresTestDatabase.connect(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + schema);
            try (ResultSet rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        b.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    out.add(b.toString());
                }
            }
        }
        return out;
    }

    private static List<String> locations(String schema) throws SQLException {
        return rows(schema, "SELECT name, type, active, is_default_receiving, is_walk_in_sale_source, is_ecommerce_fulfilment_source "
                + "FROM storage_locations ORDER BY id");
    }

    private static String storeRoomId(String schema) throws SQLException {
        return rows(schema, "SELECT id FROM storage_locations WHERE name = 'Store Room 1'").get(0);
    }

    private static String product(String schema) throws SQLException {
        String tag = Long.toString(System.nanoTime());
        return rows(schema, "INSERT INTO products (name, slug, sku, stock_quantity, created_at) VALUES ('P7', 'p7-" + tag
                + "', 'P7-" + tag + "', 0, now()) RETURNING id").get(0);
    }

    /** An existing tenant: every migration up to V50 applied, both V5 seed rows present. */
    private String existingTenant() throws SQLException {
        String s = newSchema();
        flyway(s, "50").migrate();
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
        return s;
    }

    private static void migrateAll(String schema) {
        flyway(schema, null).migrate();
    }

    @Test
    void aNewSchema_endsWithOnlyTheShopFloor_holdingAllThreeDefaultRoles() throws Exception {
        String s = newSchema();
        migrateAll(s); // provisioning: V1..V51 in one run
        assertThat(locations(s)).containsExactly(SEED_ROW);
        assertThat(rows(s, "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                + "ORDER BY installed_rank DESC LIMIT 1")).containsExactly("51");
    }

    @Test
    void anExistingTenant_unreferencedStoreRoomIsRemoved_andAReRunChangesNothing() throws Exception {
        String s = existingTenant();
        // stock on the shop floor only (V5 backfill target): the store room is still untouched
        String p = product(s);
        sql(s, "INSERT INTO location_stock (product_id, location_id, quantity_on_hand, created_at) "
                + "SELECT " + p + ", id, 7, now() FROM storage_locations WHERE name = 'Shop Floor'");
        migrateAll(s);
        assertThat(locations(s)).containsExactly(SEED_ROW);
        assertThat(rows(s, "SELECT quantity_on_hand FROM location_stock")).containsExactly("7");

        // re-runnable (replay tests delete history rows and run V51 again)
        sql(s, "DELETE FROM flyway_schema_history WHERE version = '51'");
        migrateAll(s);
        assertThat(locations(s)).containsExactly(SEED_ROW);
    }

    @Test
    void storeRoomWithALocationStockRow_isKept_evenAtQuantityZero() throws Exception {
        String s = existingTenant();
        sql(s, "INSERT INTO location_stock (product_id, location_id, quantity_on_hand, created_at) VALUES ("
                + product(s) + ", " + storeRoomId(s) + ", 0, now())");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }

    @Test
    void storeRoomThatIsTheDestinationOfATransfer_isKept() throws Exception {
        String s = existingTenant();
        sql(s, "INSERT INTO stock_transfers (product_id, from_location_id, to_location_id, quantity, transfer_type, created_at) VALUES ("
                + product(s) + ", NULL, " + storeRoomId(s) + ", 1, 'RECEIPT', now())");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }

    @Test
    void storeRoomThatIsTheSourceOfATransfer_isKept() throws Exception {
        String s = existingTenant();
        sql(s, "INSERT INTO stock_transfers (product_id, from_location_id, to_location_id, quantity, transfer_type, created_at) VALUES ("
                + product(s) + ", " + storeRoomId(s) + ", NULL, 1, 'SALE_DEDUCTION', now())");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }

    @Test
    void storeRoomRecordedAsTheFulfilmentLocationOfAnAllocation_isKept() throws Exception {
        String s = existingTenant();
        String p = product(s);
        String layer = rows(s, "INSERT INTO inventory_layers (product_id, source_type, received_quantity, remaining_quantity, unit_cost, "
                + "unit_selling_price, currency_code, received_at, created_at) VALUES (" + p + ", 'RECEIPT', 1, 1, 1, 2, 'GHS', now(), now()) "
                + "RETURNING id").get(0);
        sql(s, "INSERT INTO inventory_allocations (layer_id, product_id, reference_type, reference_id, reference_line_key, quantity, "
                + "unit_cost, unit_selling_price, currency_code, status, created_at, fulfilment_location_id, fulfilment_location_recorded) "
                + "VALUES (" + layer + ", " + p + ", 'ORDER', 'ORD-P7', 'l1', 1, 1, 2, 'GHS', 'FINALIZED', now(), " + storeRoomId(s) + ", true)");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }

    @Test
    void storeRoomHoldingADefaultRole_isKept() throws Exception {
        String s = existingTenant();
        sql(s, "UPDATE storage_locations SET is_default_receiving = FALSE");
        sql(s, "UPDATE storage_locations SET is_default_receiving = TRUE WHERE name = 'Store Room 1'");
        migrateAll(s);
        assertThat(rows(s, "SELECT name, is_default_receiving FROM storage_locations ORDER BY id"))
                .containsExactly("Shop Floor|f", "Store Room 1|t");
    }

    @Test
    void aRenamedOrRetypedStoreRoom_isTheTenantsOwnLocation_andIsKept() throws Exception {
        String renamed = existingTenant();
        sql(renamed, "UPDATE storage_locations SET name = 'Back Room' WHERE name = 'Store Room 1'");
        migrateAll(renamed);
        assertThat(rows(renamed, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Back Room");

        String retyped = existingTenant();
        sql(retyped, "UPDATE storage_locations SET type = 'SHOP_FLOOR' WHERE name = 'Store Room 1'");
        migrateAll(retyped);
        assertThat(rows(retyped, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }

    @Test
    void theOnlyLocationLeft_isNeverRemoved() throws Exception {
        String s = existingTenant();
        sql(s, "DELETE FROM storage_locations WHERE name = 'Shop Floor'");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations")).containsExactly("Store Room 1");
    }

    @Test
    void otherLocationsAreNeverTouched() throws Exception {
        String s = existingTenant();
        sql(s, "INSERT INTO storage_locations (name, type, active, created_at) VALUES ('Warehouse', 'STORE_ROOM', true, now())");
        migrateAll(s);
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Warehouse");
    }

    /**
     * Why V51 and not V21: an existing tenant has V50 applied and Flyway runs with outOfOrder=false (the app's
     * TenantSchemaMigrator default). The very same SQL numbered V21 is refused at validate-on-migrate, so every existing
     * tenant (and boot itself) would fail; numbered V51 it applies.
     */
    @Test
    void numberedBelowTheAppliedV50_theSameMigrationWouldBreakExistingTenants(@TempDir Path dir) throws Exception {
        String s = newSchema();
        config(s).target("50").load().migrate();
        Path v21 = dir.resolve("V21__single_default_storage_location.sql");
        try (var in = getClass().getResourceAsStream("/db/migrations/tenant/V51__single_default_storage_location.sql")) {
            Files.write(v21, in.readAllBytes());
        }
        // the app's tenant migrations plus the copy, default settings (outOfOrder=false): validate refuses V21, nothing runs
        Flyway withV21 = config(s).locations("classpath:db/migrations/tenant", "filesystem:" + dir).load();
        assertThatThrownBy(withV21::migrate).hasMessageContaining("21").hasMessageContaining("outOfOrder");
        assertThat(rows(s, "SELECT name FROM storage_locations ORDER BY id")).containsExactly("Shop Floor", "Store Room 1");
    }
}

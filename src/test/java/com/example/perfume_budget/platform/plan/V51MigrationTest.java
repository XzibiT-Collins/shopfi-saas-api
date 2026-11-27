package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.support.PostgresAvailableCondition;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLAN-01 AC1 / AC2: the REAL master migrations (Flyway, classpath:db/migrations/master) run into a scratch schema: up to
 * V50, legacy tenants are inserted with FREE / STARTER / PRO, then V51 runs. Asserts the backfill mapping, NOT NULL, the
 * trial for finished tenants only, the dropped column, the seed, the one-default index, the CHANGE_PLAN audit CHECK and
 * that an unknown legacy plan fails the migration instead of guessing. An empty schema migrates cleanly too.
 */
@ExtendWith(PostgresAvailableCondition.class)
class V51MigrationTest {

    private final List<String> scratch = new ArrayList<>();

    private String newSchema() {
        String s = "plan01_mig_" + System.nanoTime();
        scratch.add(s);
        return s;
    }

    private static Flyway flyway(String schema, String target) {
        TestDatabaseGuard.requireDisposable(PostgresTestDatabase.URL);
        var cfg = Flyway.configure().dataSource(PostgresTestDatabase.URL, PostgresTestDatabase.USER, PostgresTestDatabase.PASSWORD)
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migrations/master");
        if (target != null) {
            cfg = cfg.target(target);
        }
        return cfg.load();
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

    private static void legacyTenant(String schema, long id, String slug, String status, String plan) throws SQLException {
        sql(schema, "INSERT INTO tenants (id, slug, display_name, schema_name, status, plan, owner_email, created_at) VALUES ("
                + id + ", '" + slug + "', '" + slug + "', 'tenant_" + slug + "', '" + status + "', '" + plan + "', '"
                + slug + "@x.test', now())");
    }

    @Test
    void backfill_freeAndStarterToStarter_proToScale_notNull_trialForFinishedTenantsOnly_planColumnDropped() throws Exception {
        String s = newSchema();
        flyway(s, "50").migrate();
        legacyTenant(s, 1, "free-active", "ACTIVE", "FREE");
        legacyTenant(s, 2, "starter-suspended", "SUSPENDED", "STARTER");
        legacyTenant(s, 3, "pro-active", "ACTIVE", "PRO");
        legacyTenant(s, 4, "free-pending", "PENDING", "FREE");
        legacyTenant(s, 5, "pro-failed", "FAILED", "PRO");
        sql(s, "INSERT INTO tenant_audit_log (actor_admin_id, actor_email, tenant_id, tenant_slug, action, previous_status, new_status) "
                + "VALUES (1, 'a@b.c', 1, 'free-active', 'APPROVE', 'PENDING', 'ACTIVE')");
        flyway(s, null).migrate();

        assertThat(rows(s, "SELECT t.slug, p.code, t.subscription_status, t.trial_ends_at IS NOT NULL, "
                + "t.trial_ends_at BETWEEN now() + interval '14 days' - interval '1 minute' AND now() + interval '14 days' "
                + "FROM tenants t JOIN plans p ON p.id = t.plan_id ORDER BY t.id")).containsExactly(
                "free-active|STARTER|TRIALING|t|t",
                "starter-suspended|STARTER|TRIALING|t|t",
                "pro-active|SCALE|TRIALING|t|t",
                "free-pending|STARTER|TRIALING|f|null",
                "pro-failed|SCALE|TRIALING|f|null");
        assertThat(rows(s, "SELECT is_nullable FROM information_schema.columns WHERE table_schema = '" + s
                + "' AND table_name = 'tenants' AND column_name = 'plan_id'")).containsExactly("NO");
        assertThat(rows(s, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + s
                + "' AND table_name = 'tenants' AND column_name = 'plan'")).containsExactly("0");
        assertThatThrownBy(() -> sql(s, "UPDATE tenants SET plan_id = NULL WHERE id = 1")).hasMessageContaining("null");
        assertThatThrownBy(() -> sql(s, "UPDATE tenants SET plan_id = 999 WHERE id = 1")).hasMessageContaining("fk_tenants_plan");
        assertThatThrownBy(() -> sql(s, "UPDATE tenants SET subscription_status = 'FREE' WHERE id = 1"))
                .hasMessageContaining("ck_tenants_subscription_status");
        // the V6 audit CHECK now also accepts CHANGE_PLAN, and still nothing else
        sql(s, "INSERT INTO tenant_audit_log (actor_admin_id, actor_email, tenant_id, tenant_slug, action, previous_status, new_status) "
                + "VALUES (1, 'a@b.c', 1, 'free-active', 'CHANGE_PLAN', 'ACTIVE', 'ACTIVE')");
        assertThatThrownBy(() -> sql(s, "INSERT INTO tenant_audit_log (actor_admin_id, actor_email, tenant_id, tenant_slug, action, "
                + "previous_status, new_status) VALUES (1, 'a@b.c', 1, 'free-active', 'DELETE_PLAN', 'ACTIVE', 'ACTIVE')"))
                .hasMessageContaining("ck_tenant_audit_log_action");
        assertThat(rows(s, "SELECT count(*) FROM tenant_audit_log")).containsExactly("2");
    }

    @Test
    void seed_isTheOwnerCatalog_oneDefault_derivedPricesNotStored_andTheConstraintsHold() throws Exception {
        String s = newSchema();
        flyway(s, null).migrate(); // an EMPTY master: every migration incl. V51 runs cleanly
        assertThat(rows(s, "SELECT id, code, name, monthly_price_pesewas, annual_discount_percent, trial_days, transaction_fee_bps, "
                + "is_public, is_default_for_signup, highlighted, coalesce(badge_label, '-'), sort_order, active, version, "
                + "jsonb_array_length(marketing_bullets) FROM plans ORDER BY id")).containsExactly(
                "1|STARTER|Starter|14900|20|14|150|t|t|f|-|10|t|0|5",
                "2|GROWTH|Growth|39900|20|14|100|t|f|t|Most popular|20|t|0|7",
                "3|SCALE|Scale|99900|20|14|50|t|f|f|-|30|t|0|4");
        assertThat(rows(s, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + s
                + "' AND table_name = 'plans' AND column_name LIKE 'annual_price%'")).containsExactly("0");
        assertThat(rows(s, "SELECT plan_id, count(*) FROM plan_entitlements GROUP BY plan_id ORDER BY plan_id"))
                .containsExactly("1|16", "2|16", "3|16");
        // Starter: 50 products, 3 coupons, 1 location, no staff; Growth: 1000 / unlimited / 1 / 3; Scale: all unlimited
        assertThat(rows(s, "SELECT plan_id || ':' || string_agg(feature_key || '=' || coalesce(limit_value::text, 'inf'), ',' ORDER BY feature_key) "
                + "FROM plan_entitlements WHERE feature_key LIKE 'MAX_%' GROUP BY plan_id ORDER BY plan_id")).containsExactly(
                "1:MAX_ACTIVE_COUPONS=3,MAX_LOCATIONS=1,MAX_PRODUCTS=50,MAX_STAFF=0",
                "2:MAX_ACTIVE_COUPONS=inf,MAX_LOCATIONS=1,MAX_PRODUCTS=1000,MAX_STAFF=3",
                "3:MAX_ACTIVE_COUPONS=inf,MAX_LOCATIONS=inf,MAX_PRODUCTS=inf,MAX_STAFF=inf");
        assertThat(rows(s, "SELECT plan_id || ':' || string_agg(feature_key, ',' ORDER BY feature_key) FROM plan_entitlements "
                + "WHERE limit_value IS NULL AND enabled GROUP BY plan_id ORDER BY plan_id")).containsExactly(
                "2:CUSTOM_DOMAIN,INVENTORY_MANAGEMENT,LOGIN_OTP,MAX_ACTIVE_COUPONS,PRODUCT_FAMILIES,SALES_REPORTS,SHOP_WIDE_DISCOUNTS,TAX_CONFIG,THEME_ADVANCED,WALK_IN_POS",
                "3:ACCOUNTING,CUSTOM_DOMAIN,INVENTORY_MANAGEMENT,LOGIN_OTP,MAX_ACTIVE_COUPONS,MAX_LOCATIONS,MAX_PRODUCTS,MAX_STAFF,PRODUCT_FAMILIES,SALES_REPORTS,SHOP_WIDE_DISCOUNTS,STOCK_CONVERSION,STOCK_TRANSFERS,TAX_CONFIG,THEME_ADVANCED,WALK_IN_POS");
        // the first plan created later gets id 4
        sql(s, "INSERT INTO plans (code, name, monthly_price_pesewas, annual_discount_percent, trial_days, transaction_fee_bps, "
                + "is_public, sort_order, active) VALUES ('NEXT', 'Next', 0, 0, 0, 0, FALSE, 1, TRUE)");
        assertThat(rows(s, "SELECT id FROM plans WHERE code = 'NEXT'")).containsExactly("4");
        // at most one default; the default is active with a trial; bounds are CHECKs
        assertThatThrownBy(() -> sql(s, "UPDATE plans SET is_default_for_signup = TRUE WHERE id = 2")).hasMessageContaining("uq_plans_default_for_signup");
        assertThatThrownBy(() -> sql(s, "UPDATE plans SET active = FALSE WHERE id = 1")).hasMessageContaining("ck_plans_default_signup");
        assertThatThrownBy(() -> sql(s, "UPDATE plans SET trial_days = 0 WHERE id = 1")).hasMessageContaining("ck_plans_default_signup");
        for (String bad : List.of("annual_discount_percent = 101", "monthly_price_pesewas = 100000001", "transaction_fee_bps = 1001",
                "trial_days = 366", "sort_order = 1001", "code = 'lower'", "currency = 'USD'",
                "marketing_bullets = '[1,2,3,4,5,6,7,8,9,10,11,12,13]'")) {
            assertThatThrownBy(() -> sql(s, "UPDATE plans SET " + bad + " WHERE id = 2")).as(bad).hasMessageContaining("ck_plans");
        }
        assertThatThrownBy(() -> sql(s, "INSERT INTO plan_entitlements VALUES (2, 'X_LIMIT', TRUE, -1)")).hasMessageContaining("ck_plan_entitlements_limit");
        // the audit table is append-only
        sql(s, "INSERT INTO plan_audit_log (actor_admin_id, actor_email, action, plan_id, plan_code, changes) "
                + "VALUES (1, 'a@b.c', 'PLAN_UPDATE', 2, 'GROWTH', '[{\"field\":\"name\",\"before\":\"a\",\"after\":\"b\"}]')");
        assertThatThrownBy(() -> sql(s, "UPDATE plan_audit_log SET actor_email = 'x'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> sql(s, "DELETE FROM plan_audit_log")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> sql(s, "TRUNCATE plan_audit_log")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> sql(s, "INSERT INTO plan_audit_log (actor_admin_id, actor_email, action, changes) "
                + "VALUES (1, 'a@b.c', 'PLAN_DELETE', '[{}]')")).hasMessageContaining("ck_plan_audit_log_action");
        assertThatThrownBy(() -> sql(s, "INSERT INTO plan_audit_log (actor_admin_id, actor_email, action, changes) "
                + "VALUES (1, 'a@b.c', 'PLAN_UPDATE', '[]')")).hasMessageContaining("ck_plan_audit_log_changes");
    }

    @Test
    void anUnknownLegacyPlanFailsTheMigration_insteadOfPickingAPlan() throws Exception {
        String s = newSchema();
        flyway(s, "50").migrate();
        legacyTenant(s, 1, "odd-plan", "ACTIVE", "ENTERPRISE");
        assertThatThrownBy(() -> flyway(s, null).migrate()).hasMessageContaining("plan_id");
    }
}

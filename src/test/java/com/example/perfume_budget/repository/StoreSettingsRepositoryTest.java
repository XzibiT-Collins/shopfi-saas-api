package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.StoreSettings;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** THEME-01 AC1-AC4: default row per tenant, Hibernate mapping, tenant isolation, optimistic locking. */
class StoreSettingsRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired StoreSettingsRepository repo;
    @Autowired TestTenants tenants;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private <T> T tx(TestTenant t, Supplier<T> work) {
        return tenants.runAs(t, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }

    private void setPrimary(TestTenant t, String hex) {
        tx(t, () -> {
            StoreSettings s = repo.findTopByOrderByIdAsc().orElseThrow();
            ObjectNode theme = ((ObjectNode) s.getTheme()).deepCopy();
            ((ObjectNode) theme.get("tokens").get("colors")).put("primary", hex);
            s.setTheme(theme);
            return repo.saveAndFlush(s);
        });
    }

    private String primary(TestTenant t) {
        return tx(t, () -> repo.findTopByOrderByIdAsc().orElseThrow()
                .getTheme().get("tokens").get("colors").get("primary").asText());
    }

    @Test
    void templateAndTenantsGetExactlyOneDefaultRowFromMigration() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        assertThat(PostgresTestDatabase.tableExists("tenant_template", "store_settings")).isTrue();
        for (String schema : new String[]{"tenant_template", acme.schemaName(), bravo.schemaName()}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".store_settings", Integer.class))
                    .as(schema).isEqualTo(1);
        }
        StoreSettings s = tx(acme, () -> repo.findTopByOrderByIdAsc().orElseThrow());
        assertThat(s.getSchemaVersion()).isEqualTo(1);
        assertThat(s.getVersion()).isNotNull();
        assertThat(s.getTheme().get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(s.getTheme().get("tokens").get("colors").get("primary").asText()).isEqualTo("#111827");
        assertThat(s.getCreatedAt()).isNotNull();
    }

    @Test
    void tenantsHoldDifferentValuesAndAreIsolated() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        setPrimary(acme, "#0b3d2e");
        setPrimary(bravo, "#000000");

        assertThat(primary(acme)).isEqualTo("#0b3d2e");
        assertThat(primary(bravo)).isEqualTo("#000000");
        assertThat(jdbc.queryForObject("SELECT theme->'tokens'->'colors'->>'primary' FROM tenant_acme.store_settings", String.class))
                .isEqualTo("#0b3d2e");
        assertThat(jdbc.queryForObject("SELECT theme->'tokens'->'colors'->>'primary' FROM tenant_bravo.store_settings", String.class))
                .isEqualTo("#000000");
        // template (new-tenant source) untouched
        assertThat(jdbc.queryForObject("SELECT theme->'tokens'->'colors'->>'primary' FROM tenant_template.store_settings", String.class))
                .isEqualTo("#111827");
        assertThat(tx(acme, () -> repo.count())).isEqualTo(1);
    }

    @Test
    void staleWriteFailsWithOptimisticLockAndVersionIncrements() {
        TestTenant acme = tenants.acme();
        StoreSettings first = tx(acme, () -> repo.findTopByOrderByIdAsc().orElseThrow());
        StoreSettings second = tx(acme, () -> repo.findTopByOrderByIdAsc().orElseThrow());
        long v0 = first.getVersion();

        first.setUpdatedBy("admin-1");
        StoreSettings saved = tx(acme, () -> repo.saveAndFlush(first));
        assertThat(saved.getVersion()).isEqualTo(v0 + 1);

        second.setUpdatedBy("admin-2");
        assertThatThrownBy(() -> tx(acme, () -> repo.saveAndFlush(second)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(tx(acme, () -> repo.findTopByOrderByIdAsc().orElseThrow().getUpdatedBy())).isEqualTo("admin-1");
    }
}

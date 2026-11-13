package com.example.perfume_budget.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-02 AC1/AC2: full app boots on Postgres; master + tenant_template migrated; two tenants provisioned. */
class PostgresBootTest extends AbstractPostgresIntegrationTest {

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestTenants tenants;
    @Autowired CacheManager cacheManager;

    @Test
    void appBootsWithHibernateValidateAndMigratedSchemas() {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(PostgresTestDatabase.tableExists("master", "tenants")).isTrue();
        assertThat(PostgresTestDatabase.tableExists("master", "flyway_schema_history")).isTrue();
        assertThat(PostgresTestDatabase.tableExists("tenant_template", "products")).isTrue();
        assertThat(PostgresTestDatabase.tableExists("tenant_template", "flyway_schema_history")).isTrue();
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM tenant_template.flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    void createsAcmeAndBravoWithSeededAdminsInSeparateSchemas() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();

        assertThat(acme.schemaName()).isEqualTo("tenant_acme");
        assertThat(bravo.schemaName()).isEqualTo("tenant_bravo");
        assertThat(PostgresTestDatabase.tableExists("tenant_acme", "users")).isTrue();
        assertThat(PostgresTestDatabase.tableExists("tenant_bravo", "users")).isTrue();

        assertThat(jdbc.queryForList("SELECT email FROM tenant_acme.users", String.class)).containsExactly("admin@acme.test");
        assertThat(jdbc.queryForList("SELECT email FROM tenant_bravo.users", String.class)).containsExactly("admin@bravo.test");
        // template stays pristine: seeders never write to the neutral schema
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_template.users", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT slug FROM master.tenants WHERE status = 'ACTIVE' ORDER BY slug", String.class))
                .contains("acme", "bravo");

        // idempotent
        assertThat(tenants.acme()).isEqualTo(acme);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.users", Integer.class)).isEqualTo(1);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void cacheNamesArePrefixedPerTenantOverInMemoryDelegate() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        tenants.runAs(acme, () -> cacheManager.getCache("products").put("k", "acme-value"));
        tenants.runAs(bravo, () -> cacheManager.getCache("products").put("k", "bravo-value"));

        assertThat(tenants.runAs(acme, () -> cacheManager.getCache("products").get("k").get())).isEqualTo("acme-value");
        assertThat(tenants.runAs(bravo, () -> cacheManager.getCache("products").get("k").get())).isEqualTo("bravo-value");
        assertThat(cacheManager.getCacheNames()).contains("acme:products", "bravo:products");

        TenantContext.set(TenantIdentity.platform());
        try {
            Cache platform = cacheManager.getCache("products");
            assertThat(platform.get("k")).isNull();
            assertThat(platform.getName()).isEqualTo("__platform__:products");
        } finally {
            TenantContext.clear();
        }
    }
}

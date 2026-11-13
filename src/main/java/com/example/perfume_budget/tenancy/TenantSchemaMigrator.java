package com.example.perfume_budget.tenancy;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs Flyway programmatically against split locations (Spring's Flyway
 * autoconfiguration is disabled). Each schema owns its own
 * {@code flyway_schema_history}; missing schemas are created automatically.
 */
@Component
public class TenantSchemaMigrator {

    static final String MASTER_SCHEMA = "master";
    private static final String MASTER_LOCATION = "classpath:db/migrations/master";
    private static final String TENANT_LOCATION = "classpath:db/migrations/tenant";

    private final DataSource dataSource;

    public TenantSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Applies the control-plane migrations into the {@code master} schema. */
    public void migrateMaster() {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(MASTER_SCHEMA)
                .defaultSchema(MASTER_SCHEMA)
                .locations(MASTER_LOCATION)
                .load()
                .migrate();
    }

    /** Applies the tenant template migrations into the neutral validation schema. */
    public void migrateTemplate() {
        migrateTenant(TenantContext.DEFAULT_SCHEMA);
    }

    /** Applies (and creates if absent) the per-tenant template migrations into {@code schemaName}. */
    public void migrateTenant(String schemaName) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .locations(TENANT_LOCATION)
                .load()
                .migrate();
    }

    private static final java.util.regex.Pattern TENANT_SCHEMA = java.util.regex.Pattern.compile("^tenant_[a-z0-9_]{3,40}$");

    /**
     * Strict last line of defence before a schema name is interpolated into DDL (identifiers cannot be
     * bound as parameters). Only {@code tenant_[a-z0-9_]+} is accepted, never {@code tenant_template}.
     */
    static String requireTenantSchema(String schemaName) {
        if (schemaName == null || !TENANT_SCHEMA.matcher(schemaName).matches()
                || TenantContext.DEFAULT_SCHEMA.equals(schemaName)) {
            throw new IllegalArgumentException("Refusing DDL on illegal tenant schema name");
        }
        return schemaName;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** {@code CREATE SCHEMA IF NOT EXISTS} for a tenant schema (auto-committed DDL, outside any JPA tx). */
    public void createTenantSchema(String schemaName) {
        runDdl("CREATE SCHEMA IF NOT EXISTS " + quote(requireTenantSchema(schemaName)));
    }

    /** Saga compensation: {@code DROP SCHEMA IF EXISTS ... CASCADE}; idempotent. */
    public void dropTenantSchema(String schemaName) {
        runDdl("DROP SCHEMA IF EXISTS " + quote(requireTenantSchema(schemaName)) + " CASCADE");
    }

    private void runDdl(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Schema DDL failed", e);
        }
    }

    /**
     * Reads active tenant schema names directly via JDBC (runs before the EMF exists,
     * so JPA repositories are not yet available). Empty until tenants are provisioned.
     */
    public List<String> listActiveTenantSchemas() {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM " + MASTER_SCHEMA + ".tenants WHERE status = 'ACTIVE'";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list active tenant schemas from master registry", e);
        }
        return schemas;
    }
}

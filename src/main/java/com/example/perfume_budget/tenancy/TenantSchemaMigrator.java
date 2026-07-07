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

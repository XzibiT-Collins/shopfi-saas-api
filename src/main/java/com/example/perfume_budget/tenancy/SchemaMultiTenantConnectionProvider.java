package com.example.perfume_budget.tenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hibernate schema-per-tenant connection provider over the single autoconfigured
 * HikariCP {@link DataSource}. On checkout the connection's schema (PG
 * {@code search_path}) is switched to the tenant; on release it is reset to the
 * neutral {@link TenantContext#DEFAULT_SCHEMA} <em>before</em> the connection returns
 * to the pool — without this reset a connection last used by tenant A could serve
 * tenant B's request and expose A's data (risk #2 in the migration plan).
 *
 * <p>The {@code DataSource} is injected lazily via {@link ObjectProvider} to avoid a
 * circular initialization with the EntityManagerFactory during autoconfiguration.
 */
@Component
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private volatile DataSource dataSource;

    public SchemaMultiTenantConnectionProvider(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    private DataSource dataSource() {
        DataSource ds = this.dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = this.dataSource;
                if (ds == null) {
                    ds = dataSourceProvider.getObject();
                    this.dataSource = ds;
                }
            }
        }
        return ds;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection connection = dataSource().getConnection();
        connection.setSchema(TenantContext.DEFAULT_SCHEMA);
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.setSchema(TenantContext.DEFAULT_SCHEMA);
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        connection.setSchema(tenantIdentifier);
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        connection.setSchema(TenantContext.DEFAULT_SCHEMA);
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return null;
    }
}

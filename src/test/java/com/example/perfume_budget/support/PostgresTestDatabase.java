package com.example.perfume_budget.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Connection settings and schema housekeeping for tests that run against a real PostgreSQL
 * (Testcontainers is not available on every dev box, so a local server is used instead).
 *
 * <p>Settings come from environment variables, with defaults matching the local test role
 * described in QUICK_START.md:
 * <ul>
 *   <li>{@code SHOPFI_TEST_DB_URL} (default {@code jdbc:postgresql://localhost:5432/shopfi_test})</li>
 *   <li>{@code SHOPFI_TEST_DB_USER} (default {@code shopfi_test})</li>
 *   <li>{@code SHOPFI_TEST_DB_PASSWORD} (default {@code shopfi_test}; a throwaway local-only credential)</li>
 * </ul>
 * Set {@code SHOPFI_TEST_DB_REQUIRED=true} to fail (instead of skip) when the database is unreachable.
 *
 * <p>Only the schemas the app creates ({@code master}, {@code tenant_template}, {@code tenant_*}) are ever
 * dropped, so point this at a dedicated database, never at one holding real data.
 */
public final class PostgresTestDatabase {

    public static final String URL = env("SHOPFI_TEST_DB_URL", "jdbc:postgresql://localhost:5432/shopfi_test");
    public static final String USER = env("SHOPFI_TEST_DB_USER", "shopfi_test");
    public static final String PASSWORD = env("SHOPFI_TEST_DB_PASSWORD", "shopfi_test");

    private PostgresTestDatabase() {
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    public static boolean required() {
        return Boolean.parseBoolean(System.getenv("SHOPFI_TEST_DB_REQUIRED"));
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** @return null when reachable, otherwise a human-readable reason. */
    public static String unreachableReason() {
        try (Connection ignored = connect()) {
            return null;
        } catch (SQLException e) {
            return "PostgreSQL not reachable at " + URL + " as " + USER + ": " + e.getMessage();
        }
    }

    /** Drops every schema the application owns, so the next boot starts from an empty database. */
    public static void dropAppSchemas() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata "
                            + "WHERE schema_name IN ('master', 'tenant_template') OR schema_name LIKE 'tenant\\_%'")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            for (String schema : schemas) {
                st.execute("DROP SCHEMA \"" + schema.replace("\"", "\"\"") + "\" CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to drop test schemas", e);
        }
    }

    public static boolean schemaExists(String schema) {
        return scalarCount("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?", schema) > 0;
    }

    public static boolean tableExists(String schema, String table) {
        return scalarCount("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                schema, table) > 0;
    }

    private static long scalarCount(String sql, String... args) {
        try (Connection c = connect(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

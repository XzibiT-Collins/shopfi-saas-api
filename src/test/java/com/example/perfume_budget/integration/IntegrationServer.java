package com.example.perfume_budget.integration;

import com.example.perfume_budget.PerfumeBudgetApplication;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestDatabaseGuard;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Map;

/**
 * Real-stack launcher (INTEG-01): boots the REAL backend (all production beans) on a fixed port against a real
 * PostgreSQL, with only the outside world replaced: in-memory Redis, captured e-mail, a stub Paystack, fake DNS.
 * It stays up until killed, so a browser suite (frontend {@code e2e-real/}) can drive it. Test-scope only: it lives
 * under src/test and nothing in src/main refers to it.
 *
 * <pre>
 * ./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/integ.cp -Dmdep.includeScope=test
 * SHOPFI_TEST_DB_URL=jdbc:postgresql://localhost:5432/&lt;dedicated db&gt; ...  (see PostgresTestDatabase)
 * java -cp target/test-classes:target/classes:$(cat target/integ.cp) com.example.perfume_budget.integration.IntegrationServer
 * </pre>
 * Environment (all optional): INTEG_API_PORT (4910), INTEG_CONTROL_PORT (4911), INTEG_FRONTEND_PORT (3910),
 * SHOPFI_TEST_DB_URL / _USER / _PASSWORD (a DEDICATED database: every app schema in it is dropped at start).
 * Tenants are resolved from the browser Origin like in production ({@code tenant.base-domain=localhost}:
 * {@code http://acme.localhost:3910} is acme); the X-Tenant-ID dev header is OFF. Fixtures: see {@link IntegrationSeeder}.
 */
public final class IntegrationServer {

    private IntegrationServer() {
    }

    public static void main(String[] args) {
        int apiPort = port("INTEG_API_PORT", 4910);
        int controlPort = port("INTEG_CONTROL_PORT", 4911);
        int frontendPort = port("INTEG_FRONTEND_PORT", 3910);
        TestDatabaseGuard.requireDisposable(PostgresTestDatabase.URL);
        PostgresTestDatabase.dropAppSchemas();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                PostgresTestDatabase.dropAppSchemas();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }, "integration-server-cleanup"));
        settings(apiPort, controlPort, frontendPort).forEach(System::setProperty);
        new SpringApplicationBuilder(PerfumeBudgetApplication.class, IntegrationServerConfig.class)
                .profiles("test", "pgtest")
                .run(args);
    }

    /** Applied as system properties: they must beat application-test.properties (H2) and application-pgtest.properties. */
    private static Map<String, String> settings(int apiPort, int controlPort, int frontendPort) {
        return Map.ofEntries(
                        Map.entry("server.port", String.valueOf(apiPort)),
                // loopback only: the app runs with the fixed test JWT secret and published fixture credentials
                Map.entry("server.address", "127.0.0.1"),
                        Map.entry("integration.control-port", String.valueOf(controlPort)),
                        Map.entry("integration.frontend-port", String.valueOf(frontendPort)),
                        Map.entry("spring.datasource.url", PostgresTestDatabase.URL),
                        Map.entry("spring.datasource.username", PostgresTestDatabase.USER),
                        Map.entry("spring.datasource.password", PostgresTestDatabase.PASSWORD),
                        // tenant from the Origin host, never from the dev header
                        Map.entry("tenant.base-domain", "localhost"),
                        Map.entry("tenant.header-resolution.enabled", "false"),
                        Map.entry("onboarding.public-scheme", "http"),
                        Map.entry("paystack.url", "http://127.0.0.1:" + controlPort + "/paystack"),
                        Map.entry("platform.api-base-url", "http://127.0.0.1:" + apiPort),
                        Map.entry("platform.cors.allowed-origins", "http://localhost:" + frontendPort),
                        Map.entry("cors.allowed-origins", "http://localhost:" + frontendPort),
                        Map.entry("spring.main.banner-mode", "off"),
                        // fixtures log in many times from one address: lift the per-IP/email throttles of the suite only
                        Map.entry("onboarding.signup-ip.capacity", "1000"),
                        Map.entry("onboarding.signup-email.capacity", "1000"),
                        Map.entry("onboarding.verify-ip.capacity", "1000"),
                        Map.entry("onboarding.verify-email.capacity", "1000"),
                        Map.entry("onboarding.status-ip.capacity", "10000"),
                        Map.entry("onboarding.resend-ip.capacity", "1000"),
                        Map.entry("onboarding.resend-email.capacity", "1000"),
                        Map.entry("application.auth.rate-limit.resend-otp-ip.capacity", "1000"),
                        Map.entry("application.auth.rate-limit.resend-otp-email.capacity", "1000"));
    }

    private static int port(String env, int fallback) {
        String v = System.getenv(env);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
    }
}

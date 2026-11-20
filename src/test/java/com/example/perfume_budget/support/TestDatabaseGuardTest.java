package com.example.perfume_budget.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TENANT-07 AC1/AC2 + INTEG-01 low: the one rule deciding which databases the harness may wipe. */
class TestDatabaseGuardTest {

    private static final Map<String, String> NO_ENV = Map.of();

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/shopfi_test",
            "jdbc:postgresql://localhost/shopfi_test",
            "jdbc:postgresql://127.0.0.1:5432/shopfi_integ",
            "jdbc:postgresql://localhost:5432/shopfi_test_2?user=a&password=b",
            "jdbc:postgresql://localhost:5432/orders_test",
            "jdbc:postgresql://localhost:5432/Shop_INTEG",
            "jdbc:postgresql://[::1]:5432/ci_test"})
    void disposableLocalDatabasesAreAccepted(String url) {
        assertThatCode(() -> TestDatabaseGuard.requireDisposable(url, NO_ENV)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/shopfi",
            "jdbc:postgresql://localhost:5432/postgres",
            "jdbc:postgresql://localhost:5432/latest_orders",
            "jdbc:postgresql://localhost:5432/contest",
            "jdbc:postgresql://localhost:5432/integration_prod",
            "jdbc:postgresql://localhost:5432/test_prod",
            "jdbc:postgresql://localhost:5432/prod_test_backup",
            "jdbc:postgresql://localhost:5432/",
            "jdbc:postgresql://localhost:5432",
            "jdbc:postgresql:shopfi_test",
            "jdbc:mysql://localhost:3306/shopfi_test",
            "not a url", ""})
    void everythingElseIsRefusedBeforeAnyConnection(String url) {
        assertThatThrownBy(() -> TestDatabaseGuard.requireDisposable(url, NO_ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to drop schemas");
    }

    @Test
    void aTestNamedDatabaseOnARemoteHostIsRefusedUnlessOverridden() {
        String remote = "jdbc:postgresql://db.example.com:5432/shopfi_test";
        assertThatThrownBy(() -> TestDatabaseGuard.requireDisposable(remote, NO_ENV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-local host");
        assertThatThrownBy(() -> TestDatabaseGuard.requireDisposable(remote, Map.of("SHOPFI_TEST_DB_ALLOW_REMOTE", "false")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> TestDatabaseGuard.requireDisposable(remote, Map.of("SHOPFI_TEST_DB_ALLOW_REMOTE", "true")))
                .doesNotThrowAnyException();
        // the override never relaxes the name rule
        assertThatThrownBy(() -> TestDatabaseGuard.requireDisposable("jdbc:postgresql://db.example.com/prod",
                Map.of("SHOPFI_TEST_DB_ALLOW_REMOTE", "true"))).isInstanceOf(IllegalStateException.class);
    }

    /** AC2: dropAppSchemas on a rejected URL fails fast; the guard runs before a connection exists, so no DROP can be issued. */
    @Test
    void theRejectionHappensBeforeAnyConnectionIsOpened() {
        // an unreachable-but-syntactically-valid non-test URL: a guard that ran after connecting would fail with a
        // connection error instead of the refusal
        assertThatThrownBy(() -> TestDatabaseGuard.requireDisposable("jdbc:postgresql://localhost:1/production", NO_ENV))
                .hasMessageContaining("Refusing to drop schemas in database 'production'");
        assertThatThrownBy(() -> PostgresTestDatabase.dropAppSchemas("jdbc:postgresql://localhost:1/production", "u", "p"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to drop schemas")
                .hasNoCause();
        assertThat(PostgresTestDatabase.URL).as("harness default is itself a disposable URL").isNotBlank();
        assertThatCode(() -> TestDatabaseGuard.requireDisposable(PostgresTestDatabase.URL)).doesNotThrowAnyException();
    }
}

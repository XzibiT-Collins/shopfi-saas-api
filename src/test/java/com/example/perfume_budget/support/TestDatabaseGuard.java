package com.example.perfume_budget.support;

import java.net.URI;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TENANT-07 / INTEG-01: the single rule deciding whether a JDBC URL points at a disposable test database. Every
 * helper that drops {@code master} / {@code tenant_template} / {@code tenant_*} schemas calls
 * {@link #requireDisposable} first and fails fast, before any connection is opened.
 *
 * <p>Rule: the database name must match {@link #DISPOSABLE_NAME}: {@code shopfi_test} / {@code shopfi_integ} with at
 * most one short alphanumeric suffix segment ({@code shopfi_test_2}), or lower-case words joined by underscores ending in
 * {@code _test} / {@code _integ} (the existing harness names are {@code shopfi_test} and {@code shopfi_integ}); no
 * {@code _}-separated word may be one of {@link #FORBIDDEN_WORDS} ({@code prod}, {@code live}, ...); AND the host must be loopback
 * ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) unless {@code SHOPFI_TEST_DB_ALLOW_REMOTE=true} is set.
 * A name that merely contains or starts with "test" (for example {@code latest_orders}, {@code contest},
 * {@code shopfi_testing} or {@code shopfi_test_prod}) is refused (FIX-H, FIX-G review L6).
 */
public final class TestDatabaseGuard {

    static final String ALLOW_REMOTE_ENV = "SHOPFI_TEST_DB_ALLOW_REMOTE";

    static final Pattern DISPOSABLE_NAME = Pattern.compile(
            "^(?:shopfi_(?:test|integ)(?:_[a-z0-9]{1,16})?|[a-z0-9]+(?:_[a-z0-9]+)*_(?:test|integ))$");
    static final Set<String> FORBIDDEN_WORDS = Set.of("prod", "production", "live", "staging", "main");

    private TestDatabaseGuard() {
    }

    public static void requireDisposable(String jdbcUrl) {
        requireDisposable(jdbcUrl, System.getenv());
    }

    static void requireDisposable(String jdbcUrl, Map<String, String> env) {
        String name;
        String host;
        try {
            String raw = jdbcUrl == null ? "" : jdbcUrl.strip();
            if (!raw.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("not a jdbc:postgresql URL");
            }
            String rest = raw.substring("jdbc:".length());
            if (rest.startsWith("postgresql:") && !rest.startsWith("postgresql://")) {
                throw new IllegalArgumentException("no host in URL");
            }
            URI uri = new URI(rest.replaceFirst("\\?.*$", ""));
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            name = path.startsWith("/") ? path.substring(1).toLowerCase(Locale.ROOT) : path.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("Refusing to drop schemas: cannot parse the test database URL ("
                    + e.getMessage() + "); see SHOPFI_TEST_DB_URL in QUICK_START.md", e);
        }
        boolean nameOk = DISPOSABLE_NAME.matcher(name).matches()
                && Arrays.stream(name.split("_")).noneMatch(FORBIDDEN_WORDS::contains);
        if (!nameOk) {
            throw new IllegalStateException("Refusing to drop schemas in database '" + name + "': a disposable test "
                    + "database name must be \"shopfi_test\" / \"shopfi_integ\" (optionally with one short suffix) or end with "
                    + "\"_test\" / \"_integ\", in lower-case letters, digits and underscores, and contain no prod / live / "
                    + "staging / main word (see SHOPFI_TEST_DB_URL in QUICK_START.md)");
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        boolean override = "true".equalsIgnoreCase(env.get(ALLOW_REMOTE_ENV));
        if (!local && !override) {
            throw new IllegalStateException("Refusing to drop schemas on non-local host '" + host + "': test databases "
                    + "must be on localhost (set " + ALLOW_REMOTE_ENV + "=true to override; see QUICK_START.md)");
        }
    }
}

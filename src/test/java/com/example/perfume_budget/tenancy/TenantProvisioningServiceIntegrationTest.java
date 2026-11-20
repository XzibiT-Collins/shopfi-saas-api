package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.config.ChartOfAccountsSeeder;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PROV-01 AC1-AC5 against real PostgreSQL. Failure injection uses Mockito spies on the migrator and on
 * ChartOfAccountsSeeder (a seeder step that runs inside the tenant transaction). Every assertion on
 * "what is visible/committed" uses an independent JDBC connection, not the app's pool.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "tenant.header-resolution.enabled=true",
        "cors.allowed-origins=https://prova.shopfi.test,https://provb.shopfi.test,https://provc.shopfi.test"
})
class TenantProvisioningServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PASSWORD = "Pr0v!sion-pass";

    @Autowired TenantProvisioningService service;
    @Autowired TenantRepository tenantRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @MockitoSpyBean TenantSchemaMigrator migrator;
    @MockitoSpyBean ChartOfAccountsSeeder chartSeeder;
    @MockitoSpyBean TenantRepository tenantRepositorySpy;

    @AfterEach
    void cleanup() {
        Mockito.reset(migrator, chartSeeder, tenantRepositorySpy);
        TenantContext.clear();
    }

    private ProvisioningRequest req(String slug) {
        return new ProvisioningRequest(slug, "Shop " + slug, "owner@" + slug + ".test", "Owner " + slug,
                passwordEncoder.encode(PASSWORD), TenantPlan.FREE);
    }

    // ---- JDBC helpers on an independent connection -------------------------------------------------------

    private static <T> T jdbc(SqlFn<T> fn) {
        try (Connection c = PostgresTestDatabase.connect()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T jdbcOf(SqlFn<T> fn) { return jdbc(fn); }

    interface SqlFn<T> { T apply(Connection c) throws SQLException; }

    private static String statusOf(String slug) {
        return jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT status FROM master.tenants WHERE slug = ?")) {
                ps.setString(1, slug);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private static long count(String sql) {
        return jdbc(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static Set<String> tables(String schema) {
        return jdbc(c -> {
            Set<String> out = new TreeSet<>();
            try (var ps = c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    // ---- AC2 + AC5 ----------------------------------------------------------------------------------------

    @Test
    void ac5_provisionsTenant_tablesExist_adminSeeded_andCanAuthenticateViaRealAuthPath() throws Exception {
        ProvisioningResult result = service.provision(req("prova"));

        assertThat(result.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(result.schemaName()).isEqualTo("tenant_prova");
        assertThat(statusOf("prova")).isEqualTo("ACTIVE");
        assertThat(TenantContext.get()).as("context cleared").isNull();

        Set<String> expected = tables("tenant_template");
        assertThat(expected).contains("users", "products", "flyway_schema_history");
        assertThat(tables("tenant_prova")).isEqualTo(expected);

        assertThat(count("SELECT count(*) FROM tenant_prova.users WHERE email = 'owner@prova.test' AND roles = 'ADMIN'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_prova.ledger_accounts")).isEqualTo(22);
        assertThat(count("SELECT count(*) FROM tenant_prova.feature_flags")).isGreaterThan(0);
        assertThat(count("SELECT count(*) FROM tenant_prova.units_of_measure")).isEqualTo(6);
        // seeding must not leak into the neutral template or into other tenants
        assertThat(count("SELECT count(*) FROM tenant_template.users")).isZero();
        // stored credential is the supplied BCrypt hash, never plaintext
        String stored = this.<String>jdbcOf(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT password FROM tenant_prova.users WHERE email = 'owner@prova.test'")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);

        // login through the real auth path with the tenant's Origin
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "https://prova.shopfi.test")
                        .contentType("application/json")
                        .content("{\"email\":\"owner@prova.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        Cookie access = login.getResponse().getCookie("accessToken");
        assertThat(access).isNotNull();
        MvcResult me = mvc.perform(get("/api/v1/auth/me").header("Origin", "https://prova.shopfi.test").cookie(access)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString()).contains("owner@prova.test").contains("ADMIN");

        // wrong password still fails (the hash really is the credential)
        MvcResult bad = mvc.perform(post("/api/v1/auth/login").header("Origin", "https://prova.shopfi.test")
                .contentType("application/json")
                .content("{\"email\":\"owner@prova.test\",\"password\":\"wrong-password\"}")).andReturn();
        assertThat(bad.getResponse().getStatus()).isIn(400, 401, 403);
    }

    // ---- AC3 ----------------------------------------------------------------------------------------------

    @Test
    void ac3_migrateFailure_dropsSchema_marksFailed_andRetrySucceeds() {
        Mockito.doThrow(new IllegalStateException("boom-migrate")).when(migrator).migrateTenant("tenant_provb");

        assertThatThrownBy(() -> service.provision(req("provb")))
                .isInstanceOf(TenantProvisioningException.class)
                .satisfies(e -> assertThat(((TenantProvisioningException) e).isSchemaCleanedUp()).isTrue())
                .hasRootCauseMessage("boom-migrate");

        assertThat(PostgresTestDatabase.schemaExists("tenant_provb")).as("schema dropped").isFalse();
        assertThat(statusOf("provb")).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug = 'provb'")).isEqualTo(1);

        Mockito.reset(migrator);
        ProvisioningResult retry = service.provision(req("provb"));
        assertThat(retry.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(statusOf("provb")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug = 'provb'")).isEqualTo(1);
        assertThat(tables("tenant_provb")).isEqualTo(tables("tenant_template"));
        assertThat(count("SELECT count(*) FROM tenant_provb.users")).isEqualTo(1);
    }

    @Test
    void ac3_seedFailure_dropsSchema_marksFailed_contextCleared_andRetrySucceeds() {
        Mockito.doThrow(new IllegalStateException("boom-seed")).when(chartSeeder).seed();

        assertThatThrownBy(() -> service.provision(req("provc")))
                .isInstanceOf(TenantProvisioningException.class).hasRootCauseMessage("boom-seed");

        assertThat(TenantContext.get()).as("finally clears context after seed failure").isNull();
        assertThat(PostgresTestDatabase.schemaExists("tenant_provc")).isFalse();
        assertThat(statusOf("provc")).isEqualTo("FAILED");

        Mockito.reset(chartSeeder);
        assertThat(service.provision(req("provc")).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(count("SELECT count(*) FROM tenant_provc.ledger_accounts")).isEqualTo(22);
        assertThat(count("SELECT count(*) FROM tenant_provc.users")).isEqualTo(1);
    }

    @Test
    void ac3_compensationDropIsIdempotent_andRetryAfterCrashLeftSchemaBehindWorks() {
        // simulate a crash that left a half-built schema + FAILED row (compensation never ran)
        migrator.createTenantSchema("tenant_provd");
        migrator.dropTenantSchema("tenant_provd");
        migrator.dropTenantSchema("tenant_provd"); // second drop is a no-op
        migrator.createTenantSchema("tenant_provd");
        tenantRepository.save(Tenant.builder().slug("provd").displayName("d").schemaName("tenant_provd")
                .status(TenantStatus.FAILED).plan(TenantPlan.FREE).ownerEmail("owner@provd.test").build());

        assertThat(service.provision(req("provd")).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(statusOf("provd")).isEqualTo("ACTIVE");
    }

    @Test
    void ac3_failureOnAnAlreadyActiveTenant_neverDropsItsSchema() {
        service.provision(req("prove"));
        assertThatThrownBy(() -> service.provision(req("prove"))).isInstanceOf(TenantAlreadyExistsException.class);
        assertThat(statusOf("prove")).isEqualTo("ACTIVE");
        assertThat(PostgresTestDatabase.schemaExists("tenant_prove")).isTrue();
        assertThat(count("SELECT count(*) FROM tenant_prove.users")).isEqualTo(1);
    }

    @Test
    void retryByADifferentOwnerEmail_isRejected_andRowUntouched() {
        Mockito.doThrow(new IllegalStateException("x")).when(migrator).migrateTenant("tenant_provf");
        assertThatThrownBy(() -> service.provision(req("provf"))).isInstanceOf(TenantProvisioningException.class);
        Mockito.reset(migrator);
        ProvisioningRequest hijack = new ProvisioningRequest("provf", "x", "attacker@evil.test", "A",
                passwordEncoder.encode("whatever-123"), TenantPlan.FREE);
        assertThatThrownBy(() -> service.provision(hijack)).isInstanceOf(TenantAlreadyExistsException.class);
        assertThat(statusOf("provf")).isEqualTo("FAILED");
    }

    @Test
    void duplicateOwnerEmailOnAnotherSlug_isRejected_beforeAnyDdl() {
        service.provision(req("provg"));
        ProvisioningRequest dup = new ProvisioningRequest("provh", "x", "owner@provg.test", "A",
                passwordEncoder.encode("whatever-123"), TenantPlan.FREE);
        assertThatThrownBy(() -> service.provision(dup)).isInstanceOf(TenantAlreadyExistsException.class);
        assertThat(PostgresTestDatabase.schemaExists("tenant_provh")).isFalse();
        assertThat(statusOf("provh")).isNull();
    }

    // ---- AC1 against the real database ---------------------------------------------------------------------

    @Test
    void ac1_injectionSlug_isRejected_masterAndPublicSurvive_noRowWritten() {
        long before = count("SELECT count(*) FROM master.tenants");
        for (String evil : List.of("x\"; DROP SCHEMA master CASCADE;--", "public", "tenant_template", "pg_catalog")) {
            assertThatThrownBy(() -> service.provision(req(evil))).isInstanceOf(InvalidProvisioningRequestException.class);
        }
        assertThat(PostgresTestDatabase.schemaExists("master")).isTrue();
        assertThat(PostgresTestDatabase.schemaExists("tenant_template")).isTrue();
        assertThat(count("SELECT count(*) FROM master.tenants")).isEqualTo(before);
    }

    @Test
    void ddlHelpers_refuseNonTenantSchemas_evenIfCalledDirectly() {
        assertThatThrownBy(() -> migrator.dropTenantSchema("master")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migrator.dropTenantSchema("tenant_template")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migrator.dropTenantSchema("tenant_x\"; DROP SCHEMA master CASCADE;--"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PostgresTestDatabase.schemaExists("master")).isTrue();
    }

    // ---- AC4 ----------------------------------------------------------------------------------------------

    @Test
    void ac4_registryRowIsCommittedBeforeDdl_andDdlRunsWithoutAnyTransaction() {
        AtomicReference<String> statusSeenAtMigrate = new AtomicReference<>();
        AtomicReference<Boolean> txAtMigrate = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            txAtMigrate.set(TransactionSynchronizationManager.isActualTransactionActive());
            statusSeenAtMigrate.set(statusOf("provi")); // independent connection: only committed data is visible
            return inv.callRealMethod();
        }).when(migrator).migrateTenant("tenant_provi");

        service.provision(req("provi"));

        assertThat(txAtMigrate.get()).as("no JPA transaction around DDL").isFalse();
        assertThat(statusSeenAtMigrate.get()).as("PROVISIONING row committed before DDL").isEqualTo("PROVISIONING");
    }

    @Test
    void ac4_seedRunsInItsOwnTenantTransaction_andItsRollbackDoesNotTouchTheCommittedRegistryRow() {
        AtomicReference<Boolean> txAtSeed = new AtomicReference<>();
        AtomicReference<String> statusAtSeed = new AtomicReference<>();
        AtomicReference<String> contextAtSeed = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            txAtSeed.set(TransactionSynchronizationManager.isActualTransactionActive());
            statusAtSeed.set(statusOf("provj"));
            contextAtSeed.set(TenantContext.getSchemaOrDefault());
            inv.callRealMethod();                       // really inserts ledger accounts in the tenant tx...
            throw new IllegalStateException("fail-after-writes"); // ...then force the tenant tx to roll back
        }).when(chartSeeder).seed();

        assertThatThrownBy(() -> service.provision(req("provj"))).isInstanceOf(TenantProvisioningException.class);

        assertThat(txAtSeed.get()).as("seed executes inside a transaction").isTrue();
        assertThat(contextAtSeed.get()).isEqualTo("tenant_provj");
        assertThat(statusAtSeed.get()).as("master row was already committed while the tenant tx was open").isEqualTo("PROVISIONING");
        // the tenant tx rolled back and the master row is independently FAILED (not rolled back with it)
        assertThat(statusOf("provj")).isEqualTo("FAILED");
        assertThat(PostgresTestDatabase.schemaExists("tenant_provj")).isFalse();
    }

    @Test
    void ac4_refusesToRunInsideACallerTransaction() {
        List<Throwable> thrown = new ArrayList<>();
        txTemplate().executeWithoutResult(s -> {
            try {
                service.provision(req("provk"));
            } catch (Throwable t) {
                thrown.add(t);
            }
        });
        assertThat(thrown).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(statusOf("provk")).isNull();
    }

    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void callerTenantContextIsRestoredNotClobbered() {
        TenantContext.set(TenantIdentity.platform());
        service.provision(req("provl"));
        assertThat(TenantContext.isPlatform()).isTrue();
    }

    // ---- PROV-04 AC2 / AC3 -------------------------------------------------------------------------------------

    /** AC2: a schema that exists without a registry row is neither adopted, migrated into nor dropped; the row is FAILED. */
    @Test
    void anExistingOrphanSchemaIsNeverAdoptedOrDropped() {
        String slug = "orphanx";
        jdbc(c -> { try (var st = c.createStatement()) {
            st.execute("CREATE SCHEMA tenant_orphanx");
            st.execute("CREATE TABLE tenant_orphanx.keepme (id int)");
            st.execute("INSERT INTO tenant_orphanx.keepme VALUES (42)");
            return null; } });
        try {
            assertThatThrownBy(() -> service.provision(req(slug))).isInstanceOf(TenantProvisioningException.class);
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphanx")).as("orphan schema survives").isTrue();
            assertThat(count("SELECT count(*) FROM tenant_orphanx.keepme")).isEqualTo(1);
            assertThat(tables("tenant_orphanx")).as("nothing migrated into it").containsExactly("keepme");
            assertThat(statusOf(slug)).isEqualTo("FAILED");
        } finally {
            jdbc(c -> { try (var st = c.createStatement()) { st.execute("DROP SCHEMA IF EXISTS tenant_orphanx CASCADE"); return null; } });
        }
    }

    // ---- FIX-H (FIX-G review L1): orphan schema vs retry -------------------------------------------------------

    private static void createOrphan(String schema) {
        jdbc(c -> { try (var st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("CREATE TABLE " + schema + ".keepme (id int)");
            st.execute("INSERT INTO " + schema + ".keepme VALUES (7)");
            return null; } });
    }

    private static void dropSchema(String schema) {
        jdbc(c -> { try (var st = c.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); return null; } });
    }

    private void failedRow(String slug) {
        tenantRepository.save(Tenant.builder().slug(slug).displayName(slug).schemaName("tenant_" + slug)
                .status(TenantStatus.FAILED).plan(TenantPlan.FREE).ownerEmail("owner@" + slug + ".test").build());
    }

    /** The user-visible L1 scenario: CREATE SCHEMA succeeded server-side but failed client-side; the retry must not be blocked. */
    @Test
    void aRetryAfterAFailedCreateSchemaIsNotBlockedByTheOrphanItLeftBehind() {
        Mockito.doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("connection reset after CREATE"); })
                .when(migrator).createNewTenantSchema("tenant_orphr");
        try {
            assertThatThrownBy(() -> service.provision(req("orphr"))).isInstanceOf(TenantProvisioningException.class);
            assertThat(statusOf("orphr")).isEqualTo("FAILED");
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphr")).as("the failed run did not own it, so it is left").isTrue();
            Mockito.reset(migrator);
            assertThat(service.provision(req("orphr")).status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(statusOf("orphr")).isEqualTo("ACTIVE");
            assertThat(count("SELECT count(*) FROM tenant_orphr.users")).isEqualTo(1);
        } finally {
            Mockito.reset(migrator);
            dropSchema("tenant_orphr");
        }
    }

    @Test
    void aRetryOverAnEmptyOrphanAdoptsAndMigratesIt() {
        jdbc(c -> { try (var st = c.createStatement()) { st.execute("CREATE SCHEMA tenant_orphs"); return null; } });
        failedRow("orphs");
        try {
            assertThat(service.provision(req("orphs")).status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tables("tenant_orphs")).contains("users");
        } finally {
            dropSchema("tenant_orphs");
        }
    }

    /**
     * A leftover that holds foreign data cannot be migrated into (Flyway refuses a non-empty schema without a baseline): the
     * retry fails, and, unlike before FIX-H, the failure no longer DROPs the schema it merely found.
     */
    @Test
    void aRetryOverANonEmptyForeignOrphanFailsWithoutDestroyingIt() {
        createOrphan("tenant_orphn");
        failedRow("orphn");
        try {
            assertThatThrownBy(() -> service.provision(req("orphn"))).isInstanceOf(TenantProvisioningException.class);
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphn")).isTrue();
            assertThat(count("SELECT count(*) FROM tenant_orphn.keepme")).isEqualTo(1);
            assertThat(statusOf("orphn")).isEqualTo("FAILED");
        } finally {
            dropSchema("tenant_orphn");
        }
    }

    @Test
    void aFailedRetryNeverDropsAnOrphanSchemaItDidNotCreate() {
        createOrphan("tenant_orphq");
        failedRow("orphq");
        Mockito.doThrow(new IllegalStateException("flyway-boom")).when(migrator).migrateTenant("tenant_orphq");
        try {
            assertThatThrownBy(() -> service.provision(req("orphq"))).isInstanceOf(TenantProvisioningException.class);
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphq")).as("adopted orphan survives the failed retry").isTrue();
            assertThat(count("SELECT count(*) FROM tenant_orphq.keepme")).isEqualTo(1);
            assertThat(statusOf("orphq")).isEqualTo("FAILED");
        } finally {
            Mockito.reset(migrator);
            dropSchema("tenant_orphq");
        }
    }

    @Test
    void aFailedRetryDropsTheSchemaItCreatedItself() {
        failedRow("orphp");
        Mockito.doThrow(new IllegalStateException("flyway-boom")).when(migrator).migrateTenant("tenant_orphp");
        try {
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphp")).isFalse();
            assertThatThrownBy(() -> service.provision(req("orphp"))).isInstanceOf(TenantProvisioningException.class);
            assertThat(PostgresTestDatabase.schemaExists("tenant_orphp")).as("created by this run, so compensated").isFalse();
            assertThat(statusOf("orphp")).isEqualTo("FAILED");
        } finally {
            Mockito.reset(migrator);
        }
    }

    /** AC3: migrateTenant validates the name itself; it never reaches Flyway (which would create the schema) when illegal. */
    @Test
    void migrateTenantRejectsIllegalSchemaNamesBeforeFlyway() {
        for (String evil : List.of("public", "master", "tenant_x\"; DROP SCHEMA master CASCADE;--", "pg_catalog", "evil", "tenant_")) {
            assertThatThrownBy(() -> migrator.migrateTenant(evil)).as(evil).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PostgresTestDatabase.schemaExists("evil")).isFalse();
    }

    /** PLAT-02c N12: the loser of a concurrent same-slug insert (unique violation on the registry) is a lost race, not a failure. */
    @Test
    void aUniqueViolationOnTheRegistryInsertIsReportedAsAlreadyExisting() {
        Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("uq_tenants_slug"))
                .when(tenantRepositorySpy).saveAndFlush(Mockito.any(Tenant.class));
        assertThatThrownBy(() -> service.provision(req("lostrace")))
                .isInstanceOf(TenantAlreadyExistsException.class);
        assertThat(PostgresTestDatabase.schemaExists("tenant_lostrace")).as("no schema touched by the loser").isFalse();
    }

    /** PLAT-02c N12 (real race): two concurrent provisions of one slug: exactly one wins, the other is a lost race. */
    @Test
    void twoConcurrentProvisionsOfOneSlugHaveExactlyOneWinner() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Object>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    try {
                        return service.provision(req("twinrace"));
                    } catch (TenantAlreadyExistsException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            int wins = 0;
            int lost = 0;
            for (var f : fs) {
                Object r = f.get(120, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof ProvisioningResult) wins++; else lost++;
            }
            assertThat(wins).isEqualTo(1);
            assertThat(lost).isEqualTo(1);
            assertThat(statusOf("twinrace")).isEqualTo("ACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }
}

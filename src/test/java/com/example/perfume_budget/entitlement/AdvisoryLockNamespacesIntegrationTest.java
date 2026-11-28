package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.service.AdvisoryLocks;
import com.example.perfume_budget.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01c AC4: every advisory lock is the two-key form {@code (namespace, hash)} with the namespaces in ONE place
 * ({@link AdvisoryLocks}); equal hashes in different namespaces never block each other, the same namespace and hash does,
 * and the usage lock really takes the {@link AdvisoryLocks#PLAN_LIMIT} namespace.
 */
class AdvisoryLockNamespacesIntegrationTest extends AbstractEntitlementIntegrationTest {

    @Autowired UsageCounter usage;
    @Autowired PlatformTransactionManager txm;

    @Test
    void namespacesAreDistinctConstantsInOnePlace() throws Exception {
        List<Integer> values = new ArrayList<>();
        for (var f : AdvisoryLocks.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                values.add(f.getInt(null));
            }
        }
        assertThat(values).hasSizeGreaterThanOrEqualTo(6);
        assertThat(new HashSet<>(values)).as("no two namespaces share a number").hasSameSizeAs(values);
        assertThat(AdvisoryLocks.FRONT_DESK_TEMPLATE).as("pinned: the repository's native query uses the literal").isEqualTo(3);
    }

    @Test
    void noProductionCodeUsesTheSingleKeyForm_everyLockHasANamespace() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".sql")).toList()) {
                String text = Files.readString(p);
                if (text.matches("(?s).*pg_(try_)?advisory_(xact_)?lock\\(\\s*hashtext.*")
                        || text.matches("(?s).*pg_(try_)?advisory_(xact_)?lock\\(\\s*:.*")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertThat(offenders).as("single-key advisory locks (can collide across subsystems)").isEmpty();
    }

    @Test
    void equalHashesInDifferentNamespacesDoNotBlock_theSameNamespaceAndHashDoes() throws Exception {
        try (Connection a = PostgresTestDatabase.connect(); Connection b = PostgresTestDatabase.connect()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            try (Statement sa = a.createStatement(); Statement sb = b.createStatement()) {
                sa.execute("SELECT pg_advisory_xact_lock(" + AdvisoryLocks.PLAN_LIMIT + ", hashtext('same-resource'))");
                assertThat(tryLock(sb, AdvisoryLocks.THEME, "same-resource")).as("other namespace, equal hash").isTrue();
                assertThat(tryLock(sb, AdvisoryLocks.FRONT_DESK_TEMPLATE, "same-resource")).isTrue();
                assertThat(tryLock(sb, AdvisoryLocks.BILLING, "same-resource")).isTrue();
                assertThat(tryLock(sb, AdvisoryLocks.PLAN_LIMIT, "same-resource")).as("same namespace and hash").isFalse();
                assertThat(tryLock(sb, AdvisoryLocks.PLAN_LIMIT, "another-resource")).isTrue();
            } finally {
                a.rollback();
                b.rollback();
            }
        }
    }

    private static boolean tryLock(Statement st, int namespace, String resource) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_xact_lock(" + namespace + ", hashtext('" + resource + "'))")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @Test
    void theUsageLockIsTheTwoKeyFormInThePlanLimitNamespace_perTenantAndFeature() throws Exception {
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            usage.lock(42L, PlanFeature.MAX_PRODUCTS);
            try (Connection c = PostgresTestDatabase.connect(); Statement st = c.createStatement()) {
                // objsubid = 2 marks the two-key form; classid is the first key (the namespace)
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted "
                        + "AND objsubid = 2 AND classid = " + AdvisoryLocks.PLAN_LIMIT)) {
                    rs.next();
                    assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted "
                        + "AND objsubid = 1")) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("no single-key lock taken").isZero();
                }
                // and it is exactly the lock of (PLAN_LIMIT, hashtext('42:MAX_PRODUCTS')): another connection cannot take it
                assertThat(tryLock(st, AdvisoryLocks.PLAN_LIMIT, "42:MAX_PRODUCTS")).isFalse();
                assertThat(tryLock(st, AdvisoryLocks.PLAN_LIMIT, "42:MAX_STAFF")).as("another feature").isTrue();
                assertThat(tryLock(st, AdvisoryLocks.PLAN_LIMIT, "43:MAX_PRODUCTS")).as("another tenant").isTrue();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}

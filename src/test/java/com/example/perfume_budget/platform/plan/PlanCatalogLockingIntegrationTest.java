package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.plan.dto.PlanUpdateRequest;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;
import com.example.perfume_budget.platform.plan.service.PlanAdminService;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01c AC5 on PostgreSQL: a plan write that is held open (locks taken, row changed, not yet committed) does NOT make a
 * tenant plan change (the foreign-key check of {@code tenants.plan_id} takes {@code FOR KEY SHARE} on the plan row), the
 * sign-up default-plan lookup, the public list or a billing price read wait: the rows are locked {@code FOR NO KEY UPDATE}
 * and the writers are serialised with an advisory lock, not a table lock. A second plan WRITER does still wait (lock
 * timeout), so the serialisation of writes is intact.
 */
class PlanCatalogLockingIntegrationTest extends AbstractPlansIntegrationTest {

    @Autowired PlanAdminService planAdmin;
    @Autowired PlanService planService;
    @Autowired PlanRepository plans;
    @Autowired PlatformTransactionManager txm;

    /** Runs {@code body} while a real plan write of plan 2 is open (uncommitted); commits afterwards. */
    private void whilePlanWriteIsOpen(Callable<Void> body) {
        new TransactionTemplate(txm).executeWithoutResult(status -> {
            try {
                PlanUpdateRequest req = JSON.readValue(planBody(2).put("tagline", "held " + SEQ.incrementAndGet()).toString(),
                        PlanUpdateRequest.class);
                planAdmin.update(2, req, new PlatformPrincipal(admin.getId(), admin.getEmail(), "Held"));
                body.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** On a connection of its own: waits at most 1.5 s for any lock, then fails with 55P03. */
    private static <T> T otherConnection(SqlWork<T> work) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<T> f = pool.submit(() -> {
                try (Connection c = PostgresTestDatabase.connect()) {
                    c.setAutoCommit(false);
                    try (Statement st = c.createStatement()) {
                        st.execute("SET LOCAL lock_timeout = '1500ms'");
                        T r = work.run(c, st);
                        c.commit();
                        return r;
                    } catch (SQLException e) {
                        c.rollback();
                        throw e;
                    }
                }
            });
            return f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private interface SqlWork<T> {
        T run(Connection c, Statement st) throws SQLException;
    }

    @Test
    void aHeldPlanWrite_doesNotBlockATenantPlanChange_aSignupDefaultPlanRead_thePublicListOrABillingPriceRead() throws Exception {
        TestTenant t = tenants.ensureTenant("bill01c-lock");
        long tenantId = scalar("SELECT id FROM master.tenants WHERE slug = ?", "bill01c-lock");
        long before = scalar("SELECT plan_id FROM master.tenants WHERE id = ?", tenantId);
        try {
            whilePlanWriteIsOpen(() -> {
                long start = System.nanoTime();
                // tenant plan change / sign-up insert: the FK check takes FOR KEY SHARE on the plan row
                Integer updated = otherConnection((c, st) -> st.executeUpdate(
                        "UPDATE master.tenants SET plan_id = 2 WHERE id = " + tenantId));
                assertThat(updated).isEqualTo(1);
                // sign-up default-plan lookup, public list, billing price read: plain reads on their own connections
                ExecutorService pool = Executors.newFixedThreadPool(3);
                try {
                    assertThat(pool.submit(() -> planService.defaultSignupPlanId()).get(10, TimeUnit.SECONDS)).isPositive();
                    assertThat(pool.submit(() -> planService.publicPlans()).get(10, TimeUnit.SECONDS).plans()).isNotEmpty();
                    assertThat(pool.submit(() -> plans.findById(2L).orElseThrow().getMonthlyPricePesewas()).get(10, TimeUnit.SECONDS))
                            .isNotNull();
                } finally {
                    pool.shutdownNow();
                }
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                        .as("nothing waited for a lock timeout").isLessThan(10_000);
                return null;
            });
        } finally {
            exec("UPDATE master.tenants SET plan_id = ? WHERE id = ?", before, tenantId);
        }
    }

    @Test
    void aHeldPlanWrite_stillBlocksAnotherPlanWriter_andARowLockOnTheSamePlan() {
        whilePlanWriteIsOpen(() -> {
            // another writer of the catalog: the advisory lock of the catalog is held -> lock timeout (55P03)
            boolean writerWaited = lockTimeout(() -> otherConnection((c, st) -> {
                st.execute("SELECT pg_advisory_xact_lock(" + com.example.perfume_budget.service.AdvisoryLocks.PLAN_CATALOG
                        + ", hashtext('plans'))");
                return 1;
            }));
            assertThat(writerWaited).as("a second catalog writer waits").isTrue();
            // a row lock FOR NO KEY UPDATE on the plan being written is held too
            boolean rowWaited = lockTimeout(() -> otherConnection((c, st) -> {
                st.execute("SELECT id FROM master.plans WHERE id = 2 FOR NO KEY UPDATE");
                return 1;
            }));
            assertThat(rowWaited).as("the written plan row is locked against another writer").isTrue();
            return null;
        });
    }

    private static boolean lockTimeout(Callable<Integer> c) {
        try {
            c.call();
            return false;
        } catch (Exception e) {
            for (Throwable x = e; x != null; x = x.getCause()) {
                if (x instanceof SQLException s && "55P03".equals(s.getSQLState())) {
                    return true;
                }
            }
            throw new AssertionError("expected a lock timeout, got " + e, e);
        }
    }

    @Test
    void thePlanRowLockIsNoKeyUpdate_notForUpdate_inTheSource() throws Exception {
        String repo = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/perfume_budget/platform/plan/repository/PlanRepository.java"));
        assertThat(repo).contains("for no key update").doesNotContain("PESSIMISTIC_WRITE");
        String svc = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/perfume_budget/platform/plan/service/PlanAdminService.java"));
        assertThat(svc).as("no table lock on the plans (it would block the FK check of a signup)")
                .doesNotContain("LOCK TABLE");
    }
}

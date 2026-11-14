package com.example.perfume_budget.platform;

import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAT-02b AC3: {@code TenantRepository.reclaimStuckProvisioning} on a real PostgreSQL. The SQL age guard
 * ({@code updatedAt < :cutoff}) must hold on its own, independent of the service's {@code isStuck} check: a row touched a
 * minute ago must not be reclaimed even when the caller passes the normal 15 minute cutoff.
 */
class TenantReclaimRepositoryTest extends AbstractPostgresIntegrationTest {

    static final AtomicInteger SEQ = new AtomicInteger();
    static final Duration STUCK_AFTER = Duration.ofMinutes(15);

    @Autowired TenantRepository tenants;
    @Autowired PlatformTransactionManager txManager;

    private Tenant tenant(TenantStatus status, String updatedAgo) {
        int n = SEQ.incrementAndGet();
        Tenant t = tenants.saveAndFlush(Tenant.builder().slug("reclaim-" + n).displayName("Reclaim " + n)
                .schemaName("tenant_reclaim_" + n).status(status).plan(TenantPlan.FREE)
                .ownerEmail("reclaim" + n + "@p2b.test").build());
        sql("UPDATE master.tenants SET updated_at = now() - interval '" + updatedAgo + "' WHERE id = " + t.getId());
        return t;
    }

    private int reclaim(Tenant t) {
        return new TransactionTemplate(txManager).execute(s ->
                tenants.reclaimStuckProvisioning(t.getId(), LocalDateTime.now().minus(STUCK_AFTER)));
    }

    @Test
    void rowUpdatedOneMinuteAgo_isNotReclaimed_bytheSqlGuardAlone() {
        Tenant fresh = tenant(TenantStatus.PROVISIONING, "1 minute");
        assertThat(reclaim(fresh)).isZero();
        assertThat(status(fresh)).isEqualTo("PROVISIONING");
    }

    @Test
    void rowJustInsideTheWindow_isNotReclaimed_andJustOutsideIs() {
        Tenant inside = tenant(TenantStatus.PROVISIONING, "14 minutes");
        Tenant outside = tenant(TenantStatus.PROVISIONING, "16 minutes");
        assertThat(reclaim(inside)).isZero();
        assertThat(reclaim(outside)).isEqualTo(1);
        assertThat(status(inside)).isEqualTo("PROVISIONING");
        assertThat(status(outside)).isEqualTo("FAILED");
    }

    @Test
    void oldProvisioningRow_isReclaimedToFailed_exactlyOnce_andTheClockMoves() {
        Tenant stuck = tenant(TenantStatus.PROVISIONING, "3 hours");
        assertThat(reclaim(stuck)).isEqualTo(1);
        assertThat(status(stuck)).isEqualTo("FAILED");
        assertThat(reclaim(stuck)).isZero(); // second caller loses the compare-and-set
        assertThat(scalar("SELECT count(*) FROM master.tenants WHERE id = " + stuck.getId()
                + " AND updated_at > now() - interval '1 minute'")).isEqualTo(1);
    }

    @Test
    void onlyProvisioningRowsAreEverReclaimed_whateverTheirAge() {
        for (TenantStatus s : new TenantStatus[]{TenantStatus.ACTIVE, TenantStatus.PENDING, TenantStatus.SUSPENDED, TenantStatus.FAILED}) {
            Tenant t = tenant(s, "3 hours");
            assertThat(reclaim(t)).as(s.name()).isZero();
            assertThat(status(t)).isEqualTo(s.name());
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private static void sql(String sql) {
        try (Connection c = PostgresTestDatabase.connect(); var st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long scalar(String sql) {
        try (Connection c = PostgresTestDatabase.connect(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String status(Tenant t) {
        try (Connection c = PostgresTestDatabase.connect(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM master.tenants WHERE id = " + t.getId())) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

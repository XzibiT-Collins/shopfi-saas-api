package com.example.perfume_budget.isolation;

import com.example.perfume_budget.support.TestTenant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** TENANT-04 AC6: >= 50 parallel alternating A/B requests, zero cross-tenant rows, connections reset on release. */
class ConcurrentTenantLoadTest extends AbstractTenantIsolationTest {

    @Autowired DataSource dataSource;
    @Autowired com.example.perfume_budget.tenancy.SchemaMultiTenantConnectionProvider provider;

    @Test
    void ac6_parallelAlternatingRequestsNeverLeakRowsAcrossTenants() throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        for (int i = 0; i < 3; i++) {
            createProductViaApi(acme, "LoadAcme Item" + i);
            createProductViaApi(bravo, "LoadBravo Item" + i);
        }
        Cookie acmeCookie = adminCookie(acme);
        Cookie bravoCookie = adminCookie(bravo);

        int requests = 120;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> failures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            final boolean isAcme = i % 2 == 0;
            final int n = i;
            failures.add(pool.submit(() -> {
                start.await();
                TestTenant t = isAcme ? acme : bravo;
                String mine = isAcme ? "LoadAcme" : "LoadBravo";
                String other = isAcme ? "LoadBravo" : "LoadAcme";
                // mix authenticated admin listing and anonymous search
                MvcResult r = (n % 4 < 2)
                        ? mockMvc.perform(get("/api/v1/product/admin/listing").header("X-Tenant-ID", t.slug()).cookie(isAcme ? acmeCookie : bravoCookie)).andReturn()
                        : mockMvc.perform(get("/api/v1/product/search").header("X-Tenant-ID", t.slug()).param("searchTerm", "Load")).andReturn();
                String body = r.getResponse().getContentAsString();
                if (r.getResponse().getStatus() != 200) return "req " + n + " status " + r.getResponse().getStatus();
                if (body.contains(other)) return "req " + n + " (" + t.slug() + ") leaked " + other;
                if (n % 4 < 2 && !body.contains(mine)) return "req " + n + " (" + t.slug() + ") missing own rows";
                return null;
            }));
        }
        start.countDown();
        List<String> problems = new ArrayList<>();
        for (Future<String> f : failures) {
            String p = f.get(60, TimeUnit.SECONDS);
            if (p != null) problems.add(p);
        }
        pool.shutdown();
        assertThat(problems).as("cross-tenant leaks / failures under load").isEmpty();
    }

    @Test
    void ac6_connectionsAreResetToTenantTemplateOnRelease() throws Exception {
        // deterministic: check out for a tenant, observe schema, release, then every pooled connection must be neutral
        Connection c = provider.getConnection("tenant_acme");
        assertThat(c.getSchema()).isEqualTo("tenant_acme");
        provider.releaseConnection("tenant_acme", c);

        // after the concurrent-load-style churn, drain the pool: each connection handed out must be on tenant_template
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> churn = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final String schema = i % 2 == 0 ? "tenant_acme" : "tenant_bravo";
            churn.add(pool.submit(() -> {
                Connection x = provider.getConnection(schema);
                try {
                    assertThat(x.getSchema()).isEqualTo(schema);
                    x.createStatement().execute("SELECT 1");
                } finally {
                    provider.releaseConnection(schema, x);
                }
                return null;
            }));
        }
        for (Future<?> f : churn) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                Connection x = dataSource.getConnection();
                held.add(x);
                try (var st = x.createStatement(); var rs = st.executeQuery("SELECT current_schema()")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("pooled connection #%d current_schema", i).isEqualTo("tenant_template");
                }
                assertThat(x.getSchema()).isEqualTo("tenant_template");
            }
        } finally {
            for (Connection x : held) x.close();
        }
    }
}

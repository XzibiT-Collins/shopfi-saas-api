package com.example.perfume_budget.isolation;

import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** TENANT-04 AC4: @Async handlers and the webhook executor run with the submitter's TenantContext. */
class AsyncTenantContextTest extends AbstractTenantIsolationTest {

    @Autowired @Qualifier("webhookTaskExecutor") TaskExecutor webhookExecutor;
    @Autowired @Qualifier("taskExecutor") TaskExecutor defaultExecutor;
    @Autowired ApplicationEventPublisher publisher;

    private record Seen(String slug, String schema, String thread) {}

    private List<Seen> submitFromTwoTenantsConcurrently(TaskExecutor executor) throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        int perTenant = 20;
        List<Seen> seen = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(perTenant * 2);
        ExecutorService submitters = Executors.newFixedThreadPool(2);
        for (TestTenant t : List.of(acme, bravo)) {
            submitters.submit(() -> {
                try {
                    start.await();
                    tenants.runAs(t, () -> {
                        for (int i = 0; i < perTenant; i++) {
                            executor.execute(() -> {
                                seen.add(new Seen(com.example.perfume_budget.tenancy.TenantContext.getSlug(),
                                        com.example.perfume_budget.tenancy.TenantContext.getSchemaOrDefault(),
                                        Thread.currentThread().getName()));
                                done.countDown();
                            });
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        submitters.shutdownNow();
        return seen;
    }

    @Test
    void ac4_webhookExecutorCarriesCallersTenantForTwoConcurrentTenants() throws Exception {
        List<Seen> seen = submitFromTwoTenantsConcurrently(webhookExecutor);
        assertThat(seen).hasSize(40).allMatch(s -> s.thread().startsWith("webhook-"));
        assertThat(seen).filteredOn(s -> s.slug() != null && s.slug().equals("acme")).hasSize(20).allMatch(s -> s.schema().equals("tenant_acme"));
        assertThat(seen).filteredOn(s -> s.slug() != null && s.slug().equals("bravo")).hasSize(20).allMatch(s -> s.schema().equals("tenant_bravo"));
    }

    @Test
    void ac4_defaultAsyncExecutorCarriesCallersTenantForTwoConcurrentTenants() throws Exception {
        List<Seen> seen = submitFromTwoTenantsConcurrently(defaultExecutor);
        assertThat(seen).hasSize(40).allMatch(s -> s.thread().startsWith("DefaultExecutor-"));
        assertThat(seen).filteredOn(s -> "acme".equals(s.slug())).hasSize(20);
        assertThat(seen).filteredOn(s -> "bravo".equals(s.slug())).hasSize(20);
    }

    @Test
    void ac4_workerThreadContextIsClearedAfterTask() throws Exception {
        TestTenant acme = tenants.acme();
        ExecutorService single = Executors.newSingleThreadExecutor();
        // pool threads are reused: a task submitted with NO tenant must not inherit the previous tenant's context
        CountDownLatch d = new CountDownLatch(1);
        tenants.runAs(acme, () -> webhookExecutor.execute(() -> { }));
        List<String> leaked = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 12; i++) {
            webhookExecutor.execute(() -> leaked.add(String.valueOf(com.example.perfume_budget.tenancy.TenantContext.get())));
        }
        Thread.sleep(500);
        single.shutdownNow();
        assertThat(leaked).hasSize(12).containsOnly("null");
    }

    @Test
    void ac4_asyncEventHandlerWritesToCallersSchemaOnly() throws Exception {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        createProductViaApi(acme, "AsyncAcme Scent");
        createProductViaApi(bravo, "AsyncBravo Scent");
        long acmeId = jdbc.queryForObject("SELECT id FROM tenant_acme.products WHERE name = 'AsyncAcme Scent'", Long.class);
        long bravoId = jdbc.queryForObject("SELECT id FROM tenant_bravo.products WHERE name = 'AsyncBravo Scent'", Long.class);
        long acmeBefore = viewCount("tenant_acme", acmeId);
        long bravoBefore = viewCount("tenant_bravo", bravoId);

        int acmeEvents = 15, bravoEvents = 25;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> tenants.runAs(acme, () -> { for (int i = 0; i < acmeEvents; i++) publisher.publishEvent(new ViewCountEvent(acmeId)); }));
        pool.submit(() -> tenants.runAs(bravo, () -> { for (int i = 0; i < bravoEvents; i++) publisher.publishEvent(new ViewCountEvent(bravoId)); }));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(viewCount("tenant_acme", acmeId)).isEqualTo(acmeBefore + acmeEvents);
            assertThat(viewCount("tenant_bravo", bravoId)).isEqualTo(bravoBefore + bravoEvents);
        });
        pool.shutdownNow();
        // the other tenant's row with the same numeric id range is untouched by the wrong schema
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(view_count),0) FROM tenant_template.products", Long.class)).isZero();
    }

    private long viewCount(String schema, long id) {
        Long v = jdbc.queryForObject("SELECT view_count FROM " + schema + ".products WHERE id = ?", Long.class, id);
        return v == null ? 0 : v;
    }
}

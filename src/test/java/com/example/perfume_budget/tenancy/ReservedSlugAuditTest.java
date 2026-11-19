package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TENANT-14: the audit names existing tenants whose slug is now reserved and never throws. */
class ReservedSlugAuditTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger audit;

    @BeforeEach
    void captureLogs() {
        audit = (Logger) LoggerFactory.getLogger(ReservedSlugAudit.class);
        logs = new ListAppender<>();
        logs.start();
        audit.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        audit.detachAppender(logs);
    }

    private static Tenant t(String slug) {
        return Tenant.builder().slug(slug).build();
    }

    @Test
    void listsOnlyReservedSlugs() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findAll()).thenReturn(List.of(t("acme"), t("console"), t("cdn"), t("my-app"), t("www2")));
        assertThat(new ReservedSlugAudit(repo).findReservedSlugs()).containsExactly("cdn", "console", "www2");
    }

    @Test
    void aFailingLookupDoesNotBlockStartup() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findAll()).thenThrow(new IllegalStateException("db down"));
        new ReservedSlugAudit(repo).run(null);
    }

    /** FIX-A2 (4): the whole point of the audit is this ERROR line; it names the slugs (and nothing else). */
    @Test
    void runLogsOneErrorNamingTheReservedSlugs() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findAll()).thenReturn(List.of(t("acme"), t("console"), t("cdn")));
        new ReservedSlugAudit(repo).run(null);
        assertThat(logs.list).hasSize(1);
        ILoggingEvent e = logs.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
        assertThat(e.getFormattedMessage()).contains("[cdn, console]").doesNotContain("acme");
    }

    @Test
    void runIsSilentWhenNoTenantOwnsAReservedSlug() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findAll()).thenReturn(List.of(t("acme"), t("bravo")));
        new ReservedSlugAudit(repo).run(null);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aFailingLookupIsLoggedAsAWarningWithTheClassNameOnly() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findAll()).thenThrow(new IllegalStateException("db down: password=hunter2"));
        new ReservedSlugAudit(repo).run(null);
        assertThat(logs.list).hasSize(1);
        ILoggingEvent e = logs.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getFormattedMessage()).contains("IllegalStateException").doesNotContain("hunter2");
        assertThat(e.getThrowableProxy()).as("no stack trace with the message").isNull();
    }
}

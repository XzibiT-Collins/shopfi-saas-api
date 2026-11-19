package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TENANT-14: the audit names existing tenants whose slug is now reserved and never throws. */
class ReservedSlugAuditTest {

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
}

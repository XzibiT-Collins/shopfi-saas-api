package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.dto.PlatformAuditPage;
import com.example.perfume_budget.platform.dto.PlatformAuditRow;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.PlatformTenantSummary;
import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantAuditLog;
import com.example.perfume_budget.platform.repository.TenantAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** PLAT-04 unit tests: detail, audit read and list search (no database). */
class PlatformTenantReadServiceTest {

    TenantRepository tenants = mock(TenantRepository.class);
    TenantAuditLogRepository audits = mock(TenantAuditLogRepository.class);
    PlatformTenantService service;
    com.example.perfume_budget.platform.plan.repository.PlanRepository plans =
            mock(com.example.perfume_budget.platform.plan.repository.PlanRepository.class);

    @BeforeEach
    void setUp() {
        service = new PlatformTenantService(tenants, mock(TenantSignupRepository.class), audits,
                mock(TenantProvisioningService.class), mock(TenantOriginPolicy.class),
                mock(com.example.perfume_budget.tenancy.TenantSchemaMigrator.class), mock(PlatformTransactionManager.class), 1000, 15, 60,
                plans, mock(com.example.perfume_budget.platform.plan.repository.PlanAuditLogRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        com.example.perfume_budget.support.TestPlans.stubAnyId(plans);
    }

    private static Tenant tenant(String slug) {
        Tenant t = Tenant.builder().id(5L).slug(slug).displayName("Shop").schemaName("tenant_secret_schema")
                .status(TenantStatus.ACTIVE).planId(1L).ownerEmail("o@x.test")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        t.setWebhookToken("WEBHOOK-TOKEN-XYZ");
        return t;
    }

    // ---- detail

    @Test
    void detail_returnsRegistryFieldsOnly() {
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(tenant("acme")));
        PlatformTenantSummary s = service.detail("acme");
        assertThat(s.slug()).isEqualTo("acme");
        assertThat(s.toString()).doesNotContain("WEBHOOK-TOKEN-XYZ").doesNotContain("tenant_secret_schema");
    }

    @Test
    void detail_unknownAndMalformedSlugs_are404_andMalformedNeverHitTheRepository() {
        when(tenants.findBySlug("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("ghost")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Tenant not found");
        for (String bad : new String[]{"Acme", "ab", "a".repeat(41), "a_b", "a b", "../x", "", "acme%"}) {
            assertThatThrownBy(() -> service.detail(bad)).as(bad).isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Tenant not found");
        }
        verify(tenants, never()).findBySlug("Acme");
    }

    // ---- audit

    private Pageable auditPageable(Integer size, String sort, String direction) {
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(tenant("acme")));
        when(audits.findByTenantId(anyLong(), any(Pageable.class))).thenReturn(Page.empty());
        service.audit("acme", null, size, sort, direction);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(audits).findByTenantId(eq(5L), c.capture());
        return c.getValue();
    }

    @Test
    void audit_defaultsToNewestFirst_byId_withCappedSize() {
        Pageable p = auditPageable(100000, null, null);
        assertThat(p.getPageSize()).isEqualTo(100);
        assertThat(p.getSort().toList()).extracting(Sort.Order::getProperty).containsExactly("id", "id");
        assertThat(p.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void audit_defaultSize20_andOccurredAtSortKeepsIdTiebreaker() {
        Pageable p = auditPageable(null, "occurredAt", "ASC");
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort().toList()).extracting(Sort.Order::getProperty).containsExactly("occurredAt", "id");
        assertThat(p.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void audit_rejectsBadInput_beforeAnyQuery() {
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(tenant("acme")));
        assertThatThrownBy(() -> service.audit("acme", -1, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", PlatformTenantService.MAX_PAGE + 1, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("page must be <= 1000000");
        assertThatThrownBy(() -> service.audit("acme", Integer.MAX_VALUE, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, Integer.MAX_VALUE, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, PlatformTenantService.MAX_PAGE + 1, null, null, null, "x"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", null, 0, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", null, null, "actorEmail", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", null, null, "tenantId", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", null, null, "id; drop table x", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.audit("acme", null, null, null, "up")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(audits);
    }

    @Test
    void audit_unknownSlug_is404_andNoAuditQuery() {
        when(tenants.findBySlug("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.audit("ghost", null, null, null, null)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(audits);
    }

    @Test
    void audit_rowMapping_isUtcWithOffset_andPhasesMapOneToOne() {
        for (TenantAuditPhase phase : TenantAuditPhase.values()) {
            TenantAuditLog row = TenantAuditLog.builder().id(9L).occurredAt(Instant.parse("2026-11-14T10:00:05.250Z"))
                    .actorAdminId(77L).actorEmail("root@platform.test").tenantId(5L).tenantSlug("acme")
                    .action(TenantAction.REPROVISION).previousStatus(TenantStatus.FAILED)
                    .newStatus(TenantStatus.PROVISIONING).phase(phase).build();
            PlatformAuditRow r = PlatformAuditRow.from(row);
            assertThat(r.phase()).isEqualTo(phase.name());
            assertThat(r.occurredAt()).isEqualTo(OffsetDateTime.of(2026, 11, 14, 10, 0, 5, 250_000_000, ZoneOffset.UTC));
            assertThat(r.action()).isEqualTo("REPROVISION");
            assertThat(r.previousStatus()).isEqualTo("FAILED");
            assertThat(r.newStatus()).isEqualTo("PROVISIONING");
            assertThat(r.actorEmail()).isEqualTo("root@platform.test");
            assertThat(r.toString()).doesNotContain("77").doesNotContain("actorAdminId").doesNotContain("tenantId");
        }
    }

    @Test
    void audit_pageEnvelope_reportsEffectiveValues() {
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(tenant("acme")));
        TenantAuditLog row = TenantAuditLog.builder().id(1L).occurredAt(Instant.now()).actorAdminId(1L)
                .actorEmail("a@b.test").tenantId(5L).tenantSlug("acme").action(TenantAction.SUSPEND)
                .previousStatus(TenantStatus.ACTIVE).newStatus(TenantStatus.SUSPENDED).phase(TenantAuditPhase.OUTCOME).build();
        when(audits.findByTenantId(anyLong(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
        PlatformAuditPage p = service.audit("acme", 0, 5000, null, null);
        assertThat(p.size()).isEqualTo(100);
        assertThat(p.sort()).isEqualTo("id");
        assertThat(p.direction()).isEqualTo("desc");
        assertThat(p.items()).hasSize(1);
    }

    // ---- search

    @Test
    void likePattern_escapesWildcardsAndBackslash_andLeavesCaseToTheDatabase() {
        assertThat(PlatformTenantService.likePattern("100%_Off\\x")).isEqualTo("%100\\%\\_Off\\\\x%");
        assertThat(PlatformTenantService.likePattern("O'Brien")).isEqualTo("%O'Brien%");
        assertThat(PlatformTenantService.likePattern("%")).isEqualTo("%\\%%");
        assertThat(PlatformTenantService.likePattern("_")).isEqualTo("%\\_%");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_usesTheSpecificationQuery_blankMeansNoSearch_andLengthIsCapped() {
        when(tenants.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(tenants.findAll(any(Pageable.class))).thenReturn(Page.empty());
        PlatformTenantPage p = service.list(null, null, null, null, null, "  Acme  ");
        assertThat(p.totalElements()).isZero();
        verify(tenants).findAll(any(Specification.class), any(Pageable.class));
        service.list(null, null, null, null, null, "   ");
        service.list(null, null, null, null, null, null);
        verify(tenants, org.mockito.Mockito.times(2)).findAll(any(Pageable.class));
        service.list(null, null, null, null, null, "a".repeat(100)); // exactly the cap is fine
        assertThatThrownBy(() -> service.list(null, null, null, null, null, "a".repeat(101)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, null, " " + "a".repeat(101) + " "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, "ownerEmail", null, "x")).isInstanceOf(BadRequestException.class);
    }
}

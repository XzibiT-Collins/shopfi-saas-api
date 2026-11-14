package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantAuditLog;
import com.example.perfume_budget.platform.model.TenantSignup;
import com.example.perfume_budget.platform.repository.TenantAuditLogRepository;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.tenancy.TenantAlreadyExistsException;
import com.example.perfume_budget.tenancy.TenantProvisioningException;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformTenantServiceTest {

    static final PlatformPrincipal ADMIN = new PlatformPrincipal(7L, "root@platform.test", "Root");

    TenantRepository tenants = mock(TenantRepository.class);
    TenantSignupRepository signups = mock(TenantSignupRepository.class);
    TenantAuditLogRepository audits = mock(TenantAuditLogRepository.class);
    TenantProvisioningService saga = mock(TenantProvisioningService.class);
    TenantOriginPolicy cors = mock(TenantOriginPolicy.class);
    PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    PlatformTenantService service;

    @BeforeEach
    void setUp() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new PlatformTenantService(tenants, signups, audits, saga, cors, tm, 1000, 15);
    }

    private Tenant tenant(String slug, TenantStatus status) {
        return Tenant.builder().id(1L).slug(slug).displayName("Shop").schemaName("tenant_" + slug)
                .status(status).plan(TenantPlan.FREE).ownerEmail("o@" + slug + ".test")
                .createdAt(LocalDateTime.now().minusDays(1)).updatedAt(LocalDateTime.now()).build();
    }

    private Tenant stub(String slug, TenantStatus status) {
        Tenant t = tenant(slug, status);
        when(tenants.findBySlug(slug)).thenReturn(Optional.of(t));
        when(tenants.findById(1L)).thenReturn(Optional.of(t));
        return t;
    }

    private void verifiedSignup() {
        when(signups.findByTenantId(1L)).thenReturn(Optional.of(TenantSignup.builder().tenantId(1L)
                .ownerFullName("Own Er").passwordHash("$2a$12$" + "a".repeat(53)).verifiedAt(LocalDateTime.now()).build()));
    }

    private TenantAuditLog onlyAudit() {
        ArgumentCaptor<TenantAuditLog> c = ArgumentCaptor.forClass(TenantAuditLog.class);
        verify(audits, times(1)).saveAndFlush(c.capture());
        return c.getValue();
    }

    // ---- list ---------------------------------------------------------------------------------------------

    private Pageable listAndCapture(Integer size, String sort, String direction) {
        when(tenants.findAll(any(Pageable.class))).thenReturn(Page.empty());
        service.list(null, null, size, sort, direction);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(tenants).findAll(c.capture());
        return c.getValue();
    }

    @Test
    void list_cappedPageSize_defaultSize_andStableSortWithIdTiebreaker() {
        Pageable p = listAndCapture(100000, null, null);
        assertThat(p.getPageSize()).isEqualTo(PlatformTenantService.MAX_PAGE_SIZE).isEqualTo(100);
        assertThat(p.getSort().toList()).extracting(Sort.Order::getProperty).containsExactly("createdAt", "id");
        assertThat(p.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void list_defaultSizeIs20_andExactMaxIsKept() {
        assertThat(listAndCapture(null, "slug", "asc").getPageSize()).isEqualTo(20);
    }

    @Test
    void list_responseReportsEffectiveSize_andOnlyRegistryFields() {
        Tenant t = tenant("acme", TenantStatus.ACTIVE);
        t.setWebhookToken("SECRET-WEBHOOK-TOKEN");
        when(tenants.findByStatus(eq(TenantStatus.ACTIVE), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(t)));
        PlatformTenantPage page = service.list("active", 0, 5000, "slug", "asc");
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).toString()).doesNotContain("SECRET-WEBHOOK-TOKEN").doesNotContain("tenant_acme");
    }

    @Test
    void list_rejectsBadInput() {
        assertThatThrownBy(() -> service.list("bogus", null, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, -1, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, 0, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, "password; drop table", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, "slug", "sideways")).isInstanceOf(BadRequestException.class);
    }

    // ---- unknown / malformed slug --------------------------------------------------------------------------

    @Test
    void unknownAndMalformedSlugs_areTheSame404_andMalformedNeverReachTheRepository() {
        when(tenants.findBySlug("ghost-shop")).thenReturn(Optional.empty());
        ResourceNotFoundException unknown = null;
        try {
            service.suspend("ghost-shop", ADMIN);
        } catch (ResourceNotFoundException e) {
            unknown = e;
        }
        assertThat(unknown).isNotNull();
        for (String bad : new String[]{"x'; drop schema tenant_acme cascade;--", "../master", "Acme", "ab", "tenant_template",
                "a".repeat(41), "acme%00", " ", "acme\n", "ac me"}) {
            for (TenantAction a : TenantAction.values()) {
                assertThatThrownBy(() -> call(a, bad)).isInstanceOf(ResourceNotFoundException.class)
                        .hasMessage(unknown.getMessage());
            }
        }
        verify(tenants, never()).findBySlug("x'; drop schema tenant_acme cascade;--");
        verify(tenants, times(1)).findBySlug(any());
        verifyNoInteractions(audits, saga);
    }

    private TenantActionResponse call(TenantAction a, String slug) {
        return switch (a) {
            case APPROVE -> service.approve(slug, ADMIN);
            case SUSPEND -> service.suspend(slug, ADMIN);
            case REACTIVATE -> service.reactivate(slug, ADMIN);
            case REPROVISION -> service.reprovision(slug, ADMIN);
        };
    }

    // ---- suspend / reactivate ---------------------------------------------------------------------------------

    @Test
    void suspend_active_movesWithCompareAndSet_writesOneAuditRow_andInvalidatesCorsOnce() {
        stub("acme", TenantStatus.ACTIVE);
        when(tenants.transitionStatus(eq(1L), any(), eq(TenantStatus.SUSPENDED))).thenReturn(1);
        TenantActionResponse r = service.suspend("acme", ADMIN);
        assertThat(r.changed()).isTrue();
        assertThat(r.previousStatus()).isEqualTo("ACTIVE");
        verify(tenants).transitionStatus(1L, List.of(TenantStatus.ACTIVE), TenantStatus.SUSPENDED);
        TenantAuditLog a = onlyAudit();
        assertThat(a.getActorAdminId()).isEqualTo(7L);
        assertThat(a.getActorEmail()).isEqualTo("root@platform.test");
        assertThat(a.getTenantSlug()).isEqualTo("acme");
        assertThat(a.getTenantId()).isEqualTo(1L);
        assertThat(a.getAction()).isEqualTo(TenantAction.SUSPEND);
        assertThat(a.getPreviousStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(a.getNewStatus()).isEqualTo(TenantStatus.SUSPENDED);
        verify(cors, times(1)).invalidateAll();
    }

    @Test
    void reactivate_suspended_audits_andInvalidatesCors() {
        stub("acme", TenantStatus.SUSPENDED);
        when(tenants.transitionStatus(eq(1L), any(), eq(TenantStatus.ACTIVE))).thenReturn(1);
        assertThat(service.reactivate("acme", ADMIN).changed()).isTrue();
        TenantAuditLog a = onlyAudit();
        assertThat(a.getPreviousStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(a.getNewStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(cors, times(1)).invalidateAll();
    }

    @Test
    void illegalTransitions_throw409Exception_noWrite_noAudit() {
        for (TenantStatus s : List.of(TenantStatus.PENDING, TenantStatus.PROVISIONING, TenantStatus.FAILED)) {
            stub("acme", s);
            assertThatThrownBy(() -> service.suspend("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
            assertThatThrownBy(() -> service.reactivate("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        }
        stub("acme", TenantStatus.SUSPENDED);
        assertThatThrownBy(() -> service.approve("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        assertThatThrownBy(() -> service.reprovision("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        verify(tenants, never()).transitionStatus(anyLong(), any(), any());
        verifyNoInteractions(audits, saga);
    }

    @Test
    void idempotentNoOps_changeNothing_writeNoAudit() {
        stub("acme", TenantStatus.SUSPENDED);
        TenantActionResponse r = service.suspend("acme", ADMIN);
        assertThat(r.changed()).isFalse();
        stub("acme", TenantStatus.ACTIVE);
        assertThat(service.reactivate("acme", ADMIN).changed()).isFalse();
        assertThat(service.approve("acme", ADMIN).changed()).isFalse();
        verify(tenants, never()).transitionStatus(anyLong(), any(), any());
        verifyNoInteractions(audits, saga);
    }

    @Test
    void lostCompareAndSet_isANoOp_noAudit() {
        Tenant t = stub("acme", TenantStatus.ACTIVE);
        when(tenants.transitionStatus(eq(1L), any(), eq(TenantStatus.SUSPENDED))).thenAnswer(inv -> {
            t.setStatus(TenantStatus.SUSPENDED); // another admin got there first
            return 0;
        });
        TenantActionResponse r = service.suspend("acme", ADMIN);
        assertThat(r.changed()).isFalse();
        assertThat(r.tenant().status()).isEqualTo("SUSPENDED");
        verifyNoInteractions(audits);
    }

    // ---- reprovision / approve ------------------------------------------------------------------------------------

    @Test
    void reprovision_failed_runsSagaOnce_auditsOnce_dropsHash_andInvalidatesCors() {
        stub("acme", TenantStatus.FAILED);
        verifiedSignup();
        TenantActionResponse r = service.reprovision("acme", ADMIN);
        assertThat(r.changed()).isTrue();
        verify(saga, times(1)).provision(any());
        verify(signups).deleteByTenantId(1L);
        TenantAuditLog a = onlyAudit();
        assertThat(a.getAction()).isEqualTo(TenantAction.REPROVISION);
        assertThat(a.getPreviousStatus()).isEqualTo(TenantStatus.FAILED);
        assertThat(a.getNewStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(cors, times(1)).invalidateAll();
    }

    @Test
    void approve_pendingVerified_usesTheSaga() {
        stub("acme", TenantStatus.PENDING);
        verifiedSignup();
        assertThat(service.approve("acme", ADMIN).changed()).isTrue();
        verify(saga, times(1)).provision(any());
        assertThat(onlyAudit().getPreviousStatus()).isEqualTo(TenantStatus.PENDING);
    }

    @Test
    void reprovision_losingTheSagaClaim_isAnIdempotentNoOp_noAudit_noHashDelete() {
        Tenant t = stub("acme", TenantStatus.FAILED);
        verifiedSignup();
        when(saga.provision(any())).thenAnswer(inv -> {
            t.setStatus(TenantStatus.PROVISIONING);
            throw new TenantAlreadyExistsException("tenant is not in a provisionable state");
        });
        TenantActionResponse r = service.reprovision("acme", ADMIN);
        assertThat(r.changed()).isFalse();
        assertThat(r.inProgress()).isTrue();
        verifyNoInteractions(audits);
        verify(signups, never()).deleteByTenantId(anyLong());
    }

    @Test
    void reprovision_sagaFailure_auditsFailedOnce_throwsGenericError_leaksNothing() {
        stub("acme", TenantStatus.FAILED);
        verifiedSignup();
        when(saga.provision(any())).thenThrow(new TenantProvisioningException("acme", true,
                new RuntimeException("jdbc:postgresql://secret-host password=hunter2")));
        assertThatThrownBy(() -> service.reprovision("acme", ADMIN))
                .isInstanceOf(TenantProvisioningFailedException.class)
                .hasMessageNotContaining("hunter2").hasMessageNotContaining("secret-host").hasNoCause();
        TenantAuditLog a = onlyAudit();
        assertThat(a.getNewStatus()).isEqualTo(TenantStatus.FAILED);
        verify(signups, never()).deleteByTenantId(anyLong());
    }

    @Test
    void reprovision_unverifiedOrMissingCredentials_is409_andNeverProvisions() {
        stub("acme", TenantStatus.FAILED);
        when(signups.findByTenantId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reprovision("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        when(signups.findByTenantId(1L)).thenReturn(Optional.of(TenantSignup.builder().tenantId(1L)
                .ownerFullName("x").passwordHash("h").verifiedAt(null).build()));
        assertThatThrownBy(() -> service.reprovision("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        verifyNoInteractions(saga, audits);
    }

    @Test
    void reprovision_activeTenant_isNeverProvisionedAgain() {
        stub("acme", TenantStatus.ACTIVE);
        assertThatThrownBy(() -> service.reprovision("acme", ADMIN)).isInstanceOf(IllegalTenantTransitionException.class);
        verifyNoInteractions(saga, audits);
    }

    @Test
    void reprovision_freshProvisioning_isInProgressNoOp_butStuckOneIsReclaimedThenProvisioned() {
        Tenant t = stub("acme", TenantStatus.PROVISIONING);
        TenantActionResponse fresh = service.reprovision("acme", ADMIN);
        assertThat(fresh.changed()).isFalse();
        assertThat(fresh.inProgress()).isTrue();
        verifyNoInteractions(saga, audits);

        t.setUpdatedAt(LocalDateTime.now().minusHours(2));
        when(tenants.reclaimStuckProvisioning(eq(1L), any())).thenAnswer(inv -> {
            t.setStatus(TenantStatus.FAILED);
            return 1;
        });
        verifiedSignup();
        TenantActionResponse r = service.reprovision("acme", ADMIN);
        assertThat(r.changed()).isTrue();
        verify(saga, times(1)).provision(any());
        assertThat(onlyAudit().getPreviousStatus()).isEqualTo(TenantStatus.FAILED);
    }

    @Test
    void stuckReclaimLoser_doesNotProvision() {
        Tenant t = stub("acme", TenantStatus.PROVISIONING);
        t.setUpdatedAt(LocalDateTime.now().minusHours(2));
        when(tenants.reclaimStuckProvisioning(eq(1L), any())).thenAnswer(inv -> 0);
        TenantActionResponse r = service.reprovision("acme", ADMIN);
        assertThat(r.changed()).isFalse();
        verifyNoInteractions(saga, audits);
    }

    // ---- throttle -------------------------------------------------------------------------------------------

    @Test
    void perAdminActionThrottle_returns429Exception_andIsPerAdmin() {
        PlatformTenantService limited = new PlatformTenantService(tenants, signups, audits, saga, cors, tm, 2, 15);
        stub("acme", TenantStatus.SUSPENDED);
        limited.suspend("acme", ADMIN);
        limited.suspend("acme", ADMIN);
        assertThatThrownBy(() -> limited.suspend("acme", ADMIN)).isInstanceOf(RateLimitExceededException.class);
        limited.suspend("acme", new PlatformPrincipal(8L, "other@platform.test", "Other")); // separate budget
    }
}

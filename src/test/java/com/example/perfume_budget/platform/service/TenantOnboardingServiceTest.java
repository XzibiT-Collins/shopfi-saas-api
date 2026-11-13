package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.dto.OnboardingStatusResponse;
import com.example.perfume_budget.platform.dto.TenantSignupRequest;
import com.example.perfume_budget.platform.dto.TenantVerifyRequest;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.model.TenantSignup;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.repository.TenantSignupRepository;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TenantOnboardingServiceTest {

    TenantRepository tenants = mock(TenantRepository.class);
    TenantSignupRepository signups = mock(TenantSignupRepository.class);
    TenantProvisioningService provisioning = mock(TenantProvisioningService.class);
    PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TaskExecutor executor = mock(TaskExecutor.class);
    OnboardingProperties props = new OnboardingProperties();
    TenantResolutionProperties tenantProps = new TenantResolutionProperties();
    TenantOnboardingService service;

    @BeforeEach
    void setUp() {
        tenantProps.setBaseDomain("shopfi.test");
        props.setMaxOtpAttempts(3);
        service = new TenantOnboardingService(tenants, signups, provisioning, encoder, events, executor, props,
                tenantProps, mock(PlatformTransactionManager.class));
        when(tenants.saveAndFlush(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(7L);
            return t;
        });
        when(signups.saveAndFlush(any(TenantSignup.class))).thenAnswer(i -> i.getArgument(0));
    }

    private TenantSignupRequest signupReq(String slug, String password) {
        return new TenantSignupRequest(slug, "Shop", "Owner@Example.com ", "Owner Name", password, null);
    }

    // ---- signup -------------------------------------------------------------------------------------------

    @Test
    void signup_storesBcryptHashOfStrippedPassword_andHashedOtp_andPublishesOtpOnce() {
        OnboardingStatusResponse r = service.signup(signupReq("Acme-Shop", "  s3cret-pass  "));

        assertThat(r.slug()).isEqualTo("acme-shop");
        assertThat(r.status()).isEqualTo("PENDING");
        ArgumentCaptor<Tenant> t = ArgumentCaptor.forClass(Tenant.class);
        verify(tenants).saveAndFlush(t.capture());
        assertThat(t.getValue().getStatus()).isEqualTo(TenantStatus.PENDING);
        assertThat(t.getValue().getOwnerEmail()).isEqualTo("owner@example.com");
        assertThat(t.getValue().getSchemaName()).isEqualTo("tenant_acme_shop");
        assertThat(t.getValue().getPlan()).isEqualTo(TenantPlan.FREE);

        ArgumentCaptor<TenantSignup> s = ArgumentCaptor.forClass(TenantSignup.class);
        verify(signups).saveAndFlush(s.capture());
        TenantSignup saved = s.getValue();
        assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain("s3cret-pass");
        assertThat(encoder.matches("s3cret-pass", saved.getPasswordHash())).as("stripped before hashing").isTrue();

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        UserRegistrationEvent e = (UserRegistrationEvent) ev.getValue();
        assertThat(e.email()).isEqualTo("owner@example.com");
        assertThat(e.otp()).matches("\\d{6}");
        assertThat(saved.getOtpHash()).isNotEqualTo(e.otp()).hasSize(64);
        assertThat(saved.getOtpExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void signup_rejectsReservedSlugs_badPasswords_andNeverWrites() {
        for (String slug : new String[]{"admin", "public", "tenant_template", "pg-x", "a", "x;drop", "has space", "../etc"}) {
            assertThatThrownBy(() -> service.signup(signupReq(slug, "longenough1")))
                    .as(slug).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.signup(signupReq("okslug", "short"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.signup(signupReq("okslug", "x".repeat(73)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.signup(signupReq("okslug", "   short   "))).isInstanceOf(BadRequestException.class);
        verify(tenants, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void signup_duplicateSlugOrEmail_is409Exception_andSendsNoOtp() {
        when(tenants.existsBySlug("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(signupReq("taken", "longenough1"))).isInstanceOf(DuplicateResourceException.class);
        when(tenants.existsBySlug("fresh")).thenReturn(false);
        when(tenants.existsByOwnerEmail("owner@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(signupReq("fresh", "longenough1"))).isInstanceOf(DuplicateResourceException.class);
        verifyNoInteractions(events);
    }

    // ---- verify -------------------------------------------------------------------------------------------

    private TenantSignup pendingSignup(String otpPlain) {
        // produce the same salted hash the service would via a real signup call
        service.signup(signupReq("acme", "longenough1"));
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        ArgumentCaptor<TenantSignup> s = ArgumentCaptor.forClass(TenantSignup.class);
        verify(signups).saveAndFlush(s.capture());
        pendingOtp = ((UserRegistrationEvent) ev.getValue()).otp();
        Tenant t = Tenant.builder().id(7L).slug("acme").displayName("Shop").schemaName("tenant_acme")
                .status(TenantStatus.PENDING).plan(TenantPlan.FREE).ownerEmail("owner@example.com").build();
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(t));
        when(signups.findByTenantIdForUpdate(7L)).thenReturn(Optional.of(s.getValue()));
        return s.getValue();
    }

    String pendingOtp;

    private String wrongOtp() {
        return pendingOtp.equals("111111") ? "222222" : "111111";
    }

    @Test
    void verify_correctOtp_submitsProvisioningWithHashOnly_andLeavesRowPending() {
        TenantSignup s = pendingSignup(null);

        OnboardingStatusResponse r = service.verify(new TenantVerifyRequest("ACME", "OWNER@example.com", pendingOtp));

        assertThat(r.status()).isEqualTo("PROVISIONING");
        assertThat(s.getVerifiedAt()).isNotNull();
        verify(tenants, never()).transitionStatus(anyLong(), any(), any()); // the saga's CAS does the claim
        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(job.capture());
        job.getValue().run();
        ArgumentCaptor<ProvisioningRequest> pr = ArgumentCaptor.forClass(ProvisioningRequest.class);
        verify(provisioning).provision(pr.capture());
        assertThat(pr.getValue().passwordHash()).isEqualTo(s.getPasswordHash());
        assertThat(pr.getValue().toString()).doesNotContain(s.getPasswordHash());
        verify(signups).deleteByTenantId(7L); // password hash dropped after success
    }

    @Test
    void verify_wrongOtp_is400_countsAttempt_thenLocksOut_evenForTheCorrectOtp() {
        TenantSignup s = pendingSignup(null);
        TenantVerifyRequest wrong = new TenantVerifyRequest("acme", "owner@example.com", wrongOtp());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verify(wrong)).isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid or expired verification code");
        }
        assertThat(s.getFailedAttempts()).isEqualTo(3);
        assertThat(s.getLockedUntil()).isAfter(LocalDateTime.now());

        // locked: even the right code is refused and provisioning is not started
        assertThatThrownBy(() -> service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp)))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void verify_lockoutExpires_resetsCounter() {
        TenantSignup s = pendingSignup(null);
        s.setFailedAttempts(3);
        s.setLockedUntil(LocalDateTime.now().minusSeconds(1));
        service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp));
        verify(executor).execute(any());
        assertThat(s.getFailedAttempts()).isZero();
        assertThat(s.getLockedUntil()).isNull();
    }

    @Test
    void verify_expiredOtp_is400() {
        TenantSignup s = pendingSignup(null);
        s.setOtpExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void verify_unknownSlug_wrongEmail_badSlug_allLookIdentical() {
        pendingSignup(null);
        for (TenantVerifyRequest r : new TenantVerifyRequest[]{
                new TenantVerifyRequest("nope", "owner@example.com", "123456"),
                new TenantVerifyRequest("acme", "other@example.com", pendingOtp),
                new TenantVerifyRequest("admin;--", "owner@example.com", "123456")}) {
            assertThatThrownBy(() -> service.verify(r)).isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid or expired verification code");
        }
        verifyNoInteractions(executor);
    }

    @Test
    void verify_executorRejects_unverifiesAndAsks429() {
        pendingSignup(null);
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        assertThatThrownBy(() -> service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp)))
                .isInstanceOf(RateLimitExceededException.class);
        verify(signups).clearVerified(7L);
    }

    @Test
    void worker_failureBeforeClaim_clearsVerified() {
        doThrow(new IllegalStateException("x")).when(provisioning).provision(any());
        service.runProvisioning(new ProvisioningRequest("acme", "S", "o@x.com", "O", "h", TenantPlan.FREE), 7L);
        verify(signups).clearVerified(7L);
        verify(signups, never()).deleteByTenantId(anyLong());
    }

    // ---- status -------------------------------------------------------------------------------------------

    private void tenantWith(TenantStatus st) {
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(Tenant.builder().id(7L).slug("acme")
                .displayName("S").schemaName("tenant_acme").status(st).plan(TenantPlan.FREE)
                .ownerEmail("secret-owner@example.com").build()));
    }

    @Test
    void status_active_hasUrls_andLeaksNothing() {
        tenantWith(TenantStatus.ACTIVE);
        OnboardingStatusResponse r = service.status("ACME");
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.storefrontUrl()).isEqualTo("https://acme.shopfi.test");
        assertThat(r.adminLoginUrl()).isEqualTo("https://acme.shopfi.test/admin/login");
        assertThat(r.toString()).doesNotContain("secret-owner").doesNotContain("tenant_acme");
    }

    @Test
    void status_nonActive_hasNoUrls_pendingVerifiedReportsProvisioning() {
        tenantWith(TenantStatus.FAILED);
        assertThat(service.status("acme").storefrontUrl()).isNull();
        assertThat(service.status("acme").status()).isEqualTo("FAILED");

        tenantWith(TenantStatus.PENDING);
        when(signups.findByTenantId(7L)).thenReturn(Optional.of(TenantSignup.builder().verifiedAt(LocalDateTime.now()).build()));
        assertThat(service.status("acme").status()).isEqualTo("PROVISIONING");
        when(signups.findByTenantId(7L)).thenReturn(Optional.of(TenantSignup.builder().build()));
        assertThat(service.status("acme").status()).isEqualTo("PENDING");
    }

    @Test
    void status_unknownInvalidOrSuspended_is404() {
        assertThatThrownBy(() -> service.status("ghost")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.status("bad slug!")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.status(null)).isInstanceOf(ResourceNotFoundException.class);
        tenantWith(TenantStatus.SUSPENDED);
        assertThatThrownBy(() -> service.status("acme")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void status_noBaseDomain_activeWithoutUrls() {
        tenantProps.setBaseDomain("");
        tenantWith(TenantStatus.ACTIVE);
        assertThat(service.status("acme").storefrontUrl()).isNull();
    }
}

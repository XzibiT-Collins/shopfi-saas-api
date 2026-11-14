package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.platform.dto.OnboardingStatusResponse;
import com.example.perfume_budget.platform.dto.TenantResendRequest;
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

    // ---- PROV-02b AC3: single-use OTP, resend policy, redaction -----------------------------------------

    @Test
    void verify_otpIsSingleUse_replayOfTheSameCodeIsRejected() {
        TenantSignup s = pendingSignup(null);
        String oldHash = s.getOtpHash();
        service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp));
        assertThat(s.getOtpHash()).as("hash rotated on success").isNotEqualTo(oldHash);
        assertThat(s.getOtpExpiresAt()).isBefore(LocalDateTime.now());
        clearInvocations(executor);

        assertThatThrownBy(() -> service.verify(new TenantVerifyRequest("acme", "owner@example.com", pendingOtp)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(executor); // provisioning not re-submitted by a replay
    }

    @Test
    void verify_executorRejects_restoresTheOneTimeCodeSoTheOwnerCanRetry() {
        TenantSignup s = pendingSignup(null);
        doThrow(new RejectedExecutionException()).doNothing().when(executor).execute(any());
        TenantVerifyRequest req = new TenantVerifyRequest("acme", "owner@example.com", pendingOtp);
        assertThatThrownBy(() -> service.verify(req)).isInstanceOf(RateLimitExceededException.class);
        assertThat(service.verify(req).status()).isEqualTo("PROVISIONING"); // same code works again
    }

    private String resendOtp() {
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(ev.capture());
        return ((UserRegistrationEvent) ev.getValue()).otp();
    }

    private TenantSignup resendable() {
        TenantSignup s = pendingSignup(null);
        s.setLastOtpSentAt(LocalDateTime.now().minusMinutes(5));
        clearInvocations(events);
        return s;
    }

    private static TenantResendRequest resendReq() {
        return new TenantResendRequest("acme", "Owner@Example.com");
    }

    @Test
    void resend_issuesFreshOtp_oldOneStopsWorking_sentOnlyToTheStoredEmail() {
        TenantSignup s = resendable();
        String old = pendingOtp;
        s.setOtpExpiresAt(LocalDateTime.now().minusMinutes(1)); // the owner's code expired
        service.resend(resendReq());

        UserRegistrationEvent e = (UserRegistrationEvent) mockingDetails(events).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("publishEvent")).findFirst().get().getArgument(0);
        assertThat(e.email()).isEqualTo("owner@example.com");
        assertThat(e.otp()).matches("\\d{6}");
        assertThat(s.getResendCount()).isEqualTo(1);
        assertThat(s.getOtpExpiresAt()).isAfter(LocalDateTime.now());

        if (!old.equals(e.otp())) { // (1 in 900k collision otherwise)
            assertThatThrownBy(() -> service.verify(new TenantVerifyRequest("acme", "owner@example.com", old)))
                    .isInstanceOf(BadRequestException.class);
        }
        assertThat(service.verify(new TenantVerifyRequest("acme", "owner@example.com", e.otp())).status())
                .isEqualTo("PROVISIONING");
    }

    @Test
    void resend_doesNotResetAttemptsOrUnlock() {
        TenantSignup s = resendable();
        s.setFailedAttempts(2);
        service.resend(resendReq());
        assertThat(s.getFailedAttempts()).as("attacker cannot buy fresh guesses with resends").isEqualTo(2);

        clearInvocations(events);
        s.setLastOtpSentAt(LocalDateTime.now().minusMinutes(5));
        s.setFailedAttempts(3);
        s.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        String hash = s.getOtpHash();
        service.resend(resendReq());
        verifyNoInteractions(events);
        assertThat(s.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(s.getFailedAttempts()).isEqualTo(3);
        assertThat(s.getOtpHash()).isEqualTo(hash);
    }

    @Test
    void resend_isBoundedByCountAndCooldown() {
        TenantSignup s = resendable();
        service.resend(resendReq()); // 1
        verify(events).publishEvent(any(Object.class));
        service.resend(resendReq()); // inside cooldown -> silent no-op
        verify(events, times(1)).publishEvent(any(Object.class));

        for (int i = 1; i < props.getMaxResends(); i++) {
            s.setLastOtpSentAt(LocalDateTime.now().minusMinutes(5));
            service.resend(resendReq());
        }
        assertThat(s.getResendCount()).isEqualTo(props.getMaxResends());
        int sent = mockingDetails(events).getInvocations().size();
        s.setLastOtpSentAt(LocalDateTime.now().minusMinutes(5));
        service.resend(resendReq()); // cap reached
        assertThat(mockingDetails(events).getInvocations().size()).isEqualTo(sent);
    }

    @Test
    void resend_isEnumerationSafe_noOpForWrongEmailUnknownSlugBadSlugVerifiedOrProvisioningStates() {
        TenantSignup s = resendable();
        service.resend(new TenantResendRequest("acme", "other@example.com"));
        service.resend(new TenantResendRequest("nope", "owner@example.com"));
        service.resend(new TenantResendRequest("bad;slug", "owner@example.com"));
        s.setVerifiedAt(LocalDateTime.now()); // verified, provisioning in flight
        service.resend(resendReq());
        tenantWith(TenantStatus.ACTIVE);
        service.resend(resendReq());
        tenantWith(TenantStatus.PROVISIONING);
        service.resend(resendReq());
        verifyNoInteractions(events);
        assertThat(s.getResendCount()).isZero();
    }

    @Test
    void resend_forFailedTenant_issuesNewCode_andClearsStaleVerifiedMarker() {
        TenantSignup s = resendable();
        s.setVerifiedAt(LocalDateTime.now().minusMinutes(3));
        Tenant failed = Tenant.builder().id(7L).slug("acme").displayName("Shop").schemaName("tenant_acme")
                .status(TenantStatus.FAILED).plan(TenantPlan.FREE).ownerEmail("owner@example.com").build();
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(failed));
        service.resend(resendReq());
        assertThat(resendOtp()).matches("\\d{6}");
        assertThat(s.getVerifiedAt()).isNull();
    }

    @Test
    void userRegistrationEvent_toStringNeverPrintsTheOtp() {
        UserRegistrationEvent e = new UserRegistrationEvent("a@b.test", "493817");
        assertThat(e.toString()).doesNotContain("493817").contains("a@b.test").contains("redacted");
        assertThat(String.valueOf(e)).doesNotContain("493817");
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

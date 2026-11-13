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
import com.example.perfume_budget.tenancy.InvalidProvisioningRequestException;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.TenantProvisioningException;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import com.example.perfume_budget.tenancy.TenantSlugs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

/**
 * Public self-service onboarding (PLATFORM scope, no tenant context).
 *
 * <p>State machine as seen by clients: PENDING -> PROVISIONING -> ACTIVE | FAILED.
 * <ul>
 *   <li>signup: master tx inserts Tenant(PENDING) + {@link TenantSignup} (BCrypt hash of the stripped password,
 *       salted hash of a SecureRandom OTP); the OTP email event is published after commit.</li>
 *   <li>verify: master tx under a row lock checks lockout/expiry/OTP and counts failures. On success the row is
 *       <b>left PENDING</b> with {@code verified_at} set, and provisioning is submitted to a dedicated executor;
 *       {@code TenantProvisioningService.provision} itself performs the atomic PENDING/FAILED -> PROVISIONING claim
 *       (PROV-01 compare-and-set), so exactly one worker can ever provision a tenant. Status reports a verified
 *       PENDING tenant as PROVISIONING.</li>
 *   <li>the worker deletes the signup row (and so the password hash) when provisioning succeeds.</li>
 * </ul>
 * The raw password and OTP are never logged or persisted.
 */
@Slf4j
@Service
public class TenantOnboardingService {

    static final String GENERIC_VERIFY_FAILURE = "Invalid or expired verification code";
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD_BYTES = 72; // BCrypt silently truncates beyond this

    private final TenantRepository tenantRepository;
    private final TenantSignupRepository signupRepository;
    private final TenantProvisioningService provisioningService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskExecutor onboardingExecutor;
    private final OnboardingProperties props;
    private final TenantResolutionProperties tenantProps;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public TenantOnboardingService(TenantRepository tenantRepository, TenantSignupRepository signupRepository,
                                   TenantProvisioningService provisioningService, PasswordEncoder passwordEncoder,
                                   ApplicationEventPublisher eventPublisher,
                                   @Qualifier("onboardingTaskExecutor") TaskExecutor onboardingExecutor,
                                   OnboardingProperties props, TenantResolutionProperties tenantProps,
                                   PlatformTransactionManager txManager) {
        this.tenantRepository = tenantRepository;
        this.signupRepository = signupRepository;
        this.provisioningService = provisioningService;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.onboardingExecutor = onboardingExecutor;
        this.props = props;
        this.tenantProps = tenantProps;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------------------------------ signup

    public OnboardingStatusResponse signup(TenantSignupRequest req) {
        String slug = TenantSlugs.normalize(req.slug()); // allow-list; reserved/over-length -> 400
        String schemaName = TenantSlugs.schemaNameFor(slug);
        String email = normalizeEmail(req.ownerEmail());
        String password = req.password() == null ? "" : req.password().strip();
        if (password.length() < MIN_PASSWORD || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("Password must be at least " + MIN_PASSWORD + " characters and at most "
                    + MAX_PASSWORD_BYTES + " bytes");
        }
        String displayName = req.displayName().strip();
        String fullName = req.ownerFullName().strip();
        if (displayName.isEmpty() || fullName.isEmpty()) {
            throw new BadRequestException("displayName and ownerFullName must not be blank");
        }
        TenantPlan plan = req.plan() == null ? TenantPlan.FREE : req.plan();

        String hash = passwordEncoder.encode(password); // before any DB work; plaintext goes no further
        String otp = generateOtp();
        String salt = randomHex(16);
        LocalDateTime now = LocalDateTime.now();

        try {
            tx.executeWithoutResult(s -> {
                tenantRepository.deleteStalePending(slug, email, now.minusHours(props.getPendingTtlHours()));
                if (tenantRepository.existsBySlug(slug) || tenantRepository.existsByOwnerEmail(email)) {
                    throw new DuplicateResourceException("Slug or owner email is already registered");
                }
                Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                        .slug(slug).displayName(displayName).schemaName(schemaName)
                        .status(TenantStatus.PENDING).plan(plan).ownerEmail(email).build());
                signupRepository.saveAndFlush(TenantSignup.builder()
                        .tenantId(tenant.getId()).ownerFullName(fullName).passwordHash(hash)
                        .otpSalt(salt).otpHash(hashOtp(salt, otp))
                        .otpExpiresAt(now.plusMinutes(props.getOtpValidMinutes()))
                        .failedAttempts(0).resendCount(0).lastOtpSentAt(now).createdAt(now).build());
            });
        } catch (DataIntegrityViolationException race) {
            throw new DuplicateResourceException("Slug or owner email is already registered");
        }
        // After commit. Existing email path: UserRegistrationEvent -> OTPEmailSendHandler (@Async) -> EmailService.
        eventPublisher.publishEvent(new UserRegistrationEvent(email, otp));
        log.info("Onboarding signup accepted for slug {}", slug);
        return new OnboardingStatusResponse(slug, TenantStatus.PENDING.name(), null, null);
    }

    // ------------------------------------------------------------------------------------------ verify

    private enum Outcome { OK, WRONG, LOCKED }

    /** {@code consumed} carries the pre-verify OTP state so it can be restored if the executor rejects the job. */
    private record VerifyResult(Outcome outcome, ProvisioningRequest request, Long tenantId, long retryAfter,
                                String[] consumed, LocalDateTime consumedExpiry) {
        VerifyResult(Outcome outcome, ProvisioningRequest request, Long tenantId, long retryAfter) {
            this(outcome, request, tenantId, retryAfter, null, null);
        }
    }

    public OnboardingStatusResponse verify(TenantVerifyRequest req) {
        String slug = tryNormalizeSlug(req.slug());
        String email = normalizeEmail(req.email());
        String otp = req.otp() == null ? "" : req.otp().strip();
        if (slug == null) {
            throw new BadRequestException(GENERIC_VERIFY_FAILURE);
        }

        VerifyResult r = tx.execute(s -> doVerify(slug, email, otp));
        if (r == null || r.outcome() == Outcome.WRONG) {
            throw new BadRequestException(GENERIC_VERIFY_FAILURE);
        }
        if (r.outcome() == Outcome.LOCKED) {
            throw new RateLimitExceededException("Too many incorrect codes. Try again later.", r.retryAfter());
        }
        try {
            onboardingExecutor.execute(() -> runProvisioning(r.request(), r.tenantId()));
        } catch (RejectedExecutionException busy) {
            // Nothing was provisioned: hand the one-time code back so the owner can simply retry.
            tx.executeWithoutResult(s -> {
                signupRepository.clearVerified(r.tenantId());
                signupRepository.findByTenantIdForUpdate(r.tenantId()).ifPresent(su -> {
                    su.setOtpSalt(r.consumed()[0]);
                    su.setOtpHash(r.consumed()[1]);
                    su.setOtpExpiresAt(r.consumedExpiry());
                    signupRepository.saveAndFlush(su);
                });
            });
            throw new RateLimitExceededException("Onboarding is busy, retry shortly", 30);
        }
        return new OnboardingStatusResponse(slug, TenantStatus.PROVISIONING.name(), null, null);
    }

    /** Runs in a master tx. Failed attempts are returned (not thrown) so the counter commits. */
    private VerifyResult doVerify(String slug, String email, String otp) {
        LocalDateTime now = LocalDateTime.now();
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null || !tenant.getOwnerEmail().equalsIgnoreCase(email)
                || (tenant.getStatus() != TenantStatus.PENDING && tenant.getStatus() != TenantStatus.FAILED)) {
            return new VerifyResult(Outcome.WRONG, null, null, 0);
        }
        TenantSignup signup = signupRepository.findByTenantIdForUpdate(tenant.getId()).orElse(null);
        if (signup == null) {
            return new VerifyResult(Outcome.WRONG, null, null, 0);
        }
        if (signup.getLockedUntil() != null) {
            if (signup.getLockedUntil().isAfter(now)) {
                return new VerifyResult(Outcome.LOCKED, null, null,
                        Duration.between(now, signup.getLockedUntil()).toSeconds() + 1);
            }
            signup.setLockedUntil(null);
            signup.setFailedAttempts(0);
        }
        boolean valid = !signup.getOtpExpiresAt().isBefore(now)
                && !otp.isEmpty()
                && MessageDigest.isEqual(hashOtp(signup.getOtpSalt(), otp).getBytes(StandardCharsets.UTF_8),
                signup.getOtpHash().getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            int attempts = signup.getFailedAttempts() + 1;
            signup.setFailedAttempts(attempts);
            if (attempts >= props.getMaxOtpAttempts()) {
                signup.setLockedUntil(now.plusMinutes(props.getLockoutMinutes()));
            }
            signupRepository.saveAndFlush(signup);
            return new VerifyResult(Outcome.WRONG, null, null, 0);
        }
        // Single use: burn the code on success. Rotating the salt and replacing the hash with a digest of a random
        // value nobody ever sees, and expiring it, means a replay of the same OTP cannot verify again.
        String[] consumed = {signup.getOtpSalt(), signup.getOtpHash()};
        LocalDateTime consumedExpiry = signup.getOtpExpiresAt();
        String burnSalt = randomHex(16);
        signup.setOtpSalt(burnSalt);
        signup.setOtpHash(hashOtp(burnSalt, randomHex(32)));
        signup.setOtpExpiresAt(now.minusSeconds(1));
        signup.setFailedAttempts(0);
        signup.setVerifiedAt(now);
        signupRepository.saveAndFlush(signup);
        ProvisioningRequest pr = new ProvisioningRequest(tenant.getSlug(), tenant.getDisplayName(),
                tenant.getOwnerEmail(), signup.getOwnerFullName(), signup.getPasswordHash(), tenant.getPlan());
        return new VerifyResult(Outcome.OK, pr, tenant.getId(), 0, consumed, consumedExpiry);
    }

    /** Worker thread. Starts with no TenantContext (dedicated executor); provision() binds/restores its own. */
    void runProvisioning(ProvisioningRequest request, Long tenantId) {
        try {
            provisioningService.provision(request);
            tx.executeWithoutResult(s -> signupRepository.deleteByTenantId(tenantId)); // drops the password hash
        } catch (TenantProvisioningException e) {
            log.error("Async provisioning failed for {} (status FAILED, retryable)", request.slug());
        } catch (Exception e) {
            // Rejected before the saga claimed the row (e.g. already claimed, invalid input): un-verify so the
            // tenant is not reported as PROVISIONING forever.
            log.error("Async provisioning not started for {}: {}", request.slug(), e.getClass().getSimpleName());
            try {
                tx.executeWithoutResult(s -> signupRepository.clearVerified(tenantId));
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    // ------------------------------------------------------------------------------------------ resend

    /** Identical body for every outcome so the endpoint reveals nothing about which signups exist. */
    public static final String RESEND_MESSAGE = "If a pending signup matches, a new verification code has been sent";

    /**
     * Issues a fresh OTP for a PENDING or FAILED signup (the owner's code expired, was lost, or a previous
     * provisioning attempt failed). Policy, chosen so that knowing a slug and email gives an attacker no leverage:
     * <ul>
     *   <li>Silent no-op (same response as success) when: no match, wrong owner email, a verified provisioning is in
     *       flight, the cooldown has not elapsed, the per-signup resend cap is reached, or the signup is locked.</li>
     *   <li>It never clears a lockout and never resets {@code failed_attempts}: an attacker cannot buy fresh guesses
     *       by requesting codes, and cannot unlock someone else's lock for them. The counter only resets on a
     *       successful verify or when a lock expires on its own, so total guesses stay bounded.</li>
     *   <li>A new code replaces the old one (the old one stops working), is valid for the normal OTP window, and
     *       the code is only ever sent to the email stored on the signup, never to the requester's input.</li>
     *   <li>Bounded: {@code onboarding.max-resends} per signup, {@code resend-cooldown-seconds} apart, plus the
     *       per-IP/per-email rate limits in the controller. The abandoned-signup purge window is unchanged.</li>
     * </ul>
     * The cost to a legitimate owner locked out by a griefer is at most the lockout (15 min) instead of the 24 h
     * purge window.
     */
    public void resend(TenantResendRequest req) {
        String slug = tryNormalizeSlug(req.slug());
        String email = normalizeEmail(req.email());
        if (slug == null) {
            return;
        }
        String otp = tx.execute(s -> doResend(slug, email));
        if (otp != null) {
            eventPublisher.publishEvent(new UserRegistrationEvent(email, otp));
            log.info("Onboarding OTP re-sent for slug {}", slug);
        }
    }

    private String doResend(String slug, String email) {
        LocalDateTime now = LocalDateTime.now();
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null || !tenant.getOwnerEmail().equalsIgnoreCase(email)
                || (tenant.getStatus() != TenantStatus.PENDING && tenant.getStatus() != TenantStatus.FAILED)) {
            return null;
        }
        TenantSignup signup = signupRepository.findByTenantIdForUpdate(tenant.getId()).orElse(null);
        if (signup == null) {
            return null;
        }
        if (tenant.getStatus() == TenantStatus.PENDING && signup.getVerifiedAt() != null) {
            return null; // already verified, provisioning in flight
        }
        if (signup.getLockedUntil() != null && signup.getLockedUntil().isAfter(now)) {
            return null;
        }
        if (signup.getResendCount() >= props.getMaxResends()) {
            return null;
        }
        LocalDateTime lastSent = signup.getLastOtpSentAt() != null ? signup.getLastOtpSentAt() : signup.getCreatedAt();
        if (lastSent.plusSeconds(props.getResendCooldownSeconds()).isAfter(now)) {
            return null;
        }
        String otp = generateOtp();
        String salt = randomHex(16);
        signup.setOtpSalt(salt);
        signup.setOtpHash(hashOtp(salt, otp));
        signup.setOtpExpiresAt(now.plusMinutes(props.getOtpValidMinutes()));
        signup.setResendCount(signup.getResendCount() + 1);
        signup.setLastOtpSentAt(now);
        signup.setVerifiedAt(null); // a FAILED attempt may have left it set
        signupRepository.saveAndFlush(signup);
        return otp;
    }

    // ------------------------------------------------------------------------------------------ status

    public OnboardingStatusResponse status(String rawSlug) {
        String slug = tryNormalizeSlug(rawSlug);
        Tenant tenant = slug == null ? null : tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null || tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new ResourceNotFoundException("Unknown tenant");
        }
        TenantStatus st = tenant.getStatus();
        if (st == TenantStatus.PENDING) {
            boolean verified = signupRepository.findByTenantId(tenant.getId())
                    .map(s -> s.getVerifiedAt() != null).orElse(false);
            if (verified) {
                st = TenantStatus.PROVISIONING;
            }
        }
        if (st == TenantStatus.ACTIVE) {
            String base = tenantProps.getBaseDomain();
            if (base != null && !base.isBlank()) {
                String store = props.getPublicScheme() + "://" + tenant.getSlug() + "." + base;
                return new OnboardingStatusResponse(tenant.getSlug(), st.name(), store, store + props.getAdminLoginPath());
            }
        }
        return new OnboardingStatusResponse(tenant.getSlug(), st.name(), null, null);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String tryNormalizeSlug(String raw) {
        try {
            return TenantSlugs.normalize(raw);
        } catch (InvalidProvisioningRequestException e) {
            return null;
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /** 6-digit OTP from SecureRandom (uniform over 100000-999999). */
    private String generateOtp() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String hashOtp(String salt, String otp) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest((salt + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.example.perfume_budget.service;


import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.service.interfaces.OtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private static final long LOGIN_OTP_EXPIRATION_MINUTES = 5;
    private static final long RESET_PASSWORD_TOKEN_EXPIRATION_MINUTES = 15;
    /** Wrong guesses allowed per issued code; the code is destroyed on the last one (a new code must be requested). */
    static final int MAX_OTP_ATTEMPTS = 5;
    // Every key embeds the tenant scope (see buildKey()): the same email on two tenants has independent OTPs,
    // and a login challenge, OTP or reset token issued for one tenant can never be redeemed on another.
    private static final String KEY_ROOT = "otp";
    private static final String REGISTRATION_OTP_KIND = "registration";
    private static final String LOGIN_OTP_CODE_KIND = "login:code";
    private static final String LOGIN_OTP_EMAIL_KIND = "login:email";
    private static final String RESET_PASSWORD_KIND = "reset";
    private static final String ATTEMPTS_PREFIX = "otp:attempts:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public void sendRegistrationOtp(String email){
        String otp = generateRegistrationOtp(email);
        UserRegistrationEvent event = new UserRegistrationEvent(email, otp);
        eventPublisher.publishEvent(event);
    }

    @Override
    public String initiateLoginOtp(String email) {
        String challengeToken = UUID.randomUUID().toString();
        String otp = generateLoginOtp(challengeToken, email);
        eventPublisher.publishEvent(new LoginOtpEvent(email, otp));
        return challengeToken;
    }

    @Override
    public boolean verifyLoginOtp(String challengeToken, String otp) {
        String codeKey = buildLoginOtpCodeKey(challengeToken);
        return verifyWithAttemptCap(codeKey, ATTEMPTS_PREFIX + codeKey, otp,
                buildLoginOtpEmailKey(challengeToken));
    }

    @Override
    public void resendLoginOtp(String challengeToken) {
        String email = getEmailFromLoginChallenge(challengeToken);
        if (email == null) {
            throw new BadRequestException("Invalid or expired login challenge");
        }
        String otp = generateLoginOtp(challengeToken, email);
        eventPublisher.publishEvent(new LoginOtpEvent(email, otp));
    }

    @Override
    public void requestResetPasswordToken(String email, String fullName){
        String tenantSlug = tenantScope(); // fail before any Redis write when there is no tenant (platform scope has no reset flow)
        String resetPasswordToken = generatePasswordResetToken(email);
        ResetPasswordEvent event = new ResetPasswordEvent(email, fullName, resetPasswordToken, tenantSlug);
        eventPublisher.publishEvent(event);
    }

    @Override
    public void invalidateResetPasswordToken(String passwordResetToken) {
        redisTemplate.delete(buildResetKey(passwordResetToken));
    }

    @Override
    public boolean verifyRegistrationOtp(String email, String otp) {
        String codeKey = buildRegistrationOtpKey(email);
        return verifyWithAttemptCap(codeKey, ATTEMPTS_PREFIX + codeKey, otp);
    }

    @Override
    public void issueDecoyRegistrationOtp(String email) {
        // Same work as sendRegistrationOtp (secure random + Redis write) minus the email, so a request for an
        // unknown or already verified address is indistinguishable in response and timing class.
        generateRegistrationOtp(email);
    }

    @Override
    public String getEmailFromLoginChallenge(String challengeToken) {
        Object storedEmail = redisTemplate.opsForValue().get(buildLoginOtpEmailKey(challengeToken));
        return storedEmail != null ? storedEmail.toString() : null;
    }

    @Override
    public boolean verifyResetPasswordToken(String passwordResetToken) {
        if (passwordResetToken == null || passwordResetToken.isBlank()) {
            return false;
        }
        Object storedToken = redisTemplate.opsForValue().get(buildResetKey(passwordResetToken));
        return storedToken != null;
    }

    @Override
    public String getEmailFromResetPasswordToken(String passwordResetToken) {
        if (passwordResetToken == null || passwordResetToken.isBlank()) {
            return null;
        }
        return (String) redisTemplate.opsForValue().get(buildResetKey(passwordResetToken));
    }


//    HELPER METHODS

    private String generateRegistrationOtp(String email) {
        String otp = generateOtp();
        String key = buildRegistrationOtpKey(email);
        redisTemplate.opsForValue().set(key, otp, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        redisTemplate.delete(ATTEMPTS_PREFIX + key); // a fresh code starts with a fresh attempt budget
        return otp;
    }

    private String generateLoginOtp(String challengeToken, String email) {
        String otp = generateOtp();
        redisTemplate.opsForValue().set(buildLoginOtpCodeKey(challengeToken), otp, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        redisTemplate.delete(ATTEMPTS_PREFIX + buildLoginOtpCodeKey(challengeToken));
        redisTemplate.opsForValue().set(buildLoginOtpEmailKey(challengeToken), email, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        return otp;
    }

    private String generateOtp() {
        // SecureRandom, uniform over 100000-999999 (ThreadLocalRandom is predictable from a few outputs).
        return String.valueOf(100000 + SECURE_RANDOM.nextInt(900000));
    }

    private String generateEncodedToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String hashToken(String token){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void saveResetPasswordToken(String hashedToken, String email){
        redisTemplate.opsForValue().set(buildResetKey(hashedToken), email, Duration.ofMinutes(RESET_PASSWORD_TOKEN_EXPIRATION_MINUTES));
    }

    private String generatePasswordResetToken(String email){
        String hashedToken = hashToken(generateEncodedToken());
        saveResetPasswordToken(hashedToken, email);
        return hashedToken;
    }

    /**
     * Checks {@code otp} against the stored code in constant time and enforces {@link #MAX_OTP_ATTEMPTS}: the counter
     * is an atomic INCR, and on the last allowed wrong guess the code (and any {@code alsoDelete} keys) is destroyed
     * so it can no longer be brute-forced; the user must request a new one.
     */
    private boolean verifyWithAttemptCap(String codeKey, String attemptsKey, String otp, String... alsoDelete) {
        Object stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            return false;
        }
        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(attemptsKey, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        }
        boolean matches = otp != null && MessageDigest.isEqual(
                stored.toString().getBytes(StandardCharsets.UTF_8), otp.getBytes(StandardCharsets.UTF_8));
        if (matches && (attempts == null || attempts <= MAX_OTP_ATTEMPTS)) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(attemptsKey);
            for (String k : alsoDelete) {
                redisTemplate.delete(k);
            }
            return true;
        }
        if (attempts != null && attempts >= MAX_OTP_ATTEMPTS) {
            log.warn("OTP attempt cap reached; code invalidated");
            redisTemplate.delete(codeKey);
            for (String k : alsoDelete) {
                redisTemplate.delete(k);
            }
        }
        return false;
    }

    /** Tenant slug of the current request (also the event payload); a tenant-less OTP/reset operation is a programming error. */
    private static String tenantScope() {
        String slug = TenantContext.getSlug();
        if (slug == null || TenantContext.PLATFORM_SLUG.equals(slug)) {
            throw new IllegalStateException("OTP and reset-token operations require a resolved tenant");
        }
        return slug;
    }

    /**
     * Single place where every OTP/reset Redis key is built (TENANT-09 scheme). The key is always scoped by the
     * current request's tenant (same prefix the cache layer uses), so a challenge token, OTP or reset token minted
     * for one tenant can never be looked up, redeemed or overwritten from another tenant. Format:
     * {@code otp:<kind>:<tenant>:<id>}. Fails closed (AUTH-01, PO decision) unless a TENANT-scope context is bound: no
     * context or PLATFORM scope throws (platform admins never use customer OTPs).
     */
    private String buildKey(String kind, String id) {
        tenantScope(); // fail closed: no context or PLATFORM scope -> IllegalStateException
        return KEY_ROOT + ":" + kind + ":" + TenantContext.getCachePrefix() + ":" + id;
    }

    private String buildRegistrationOtpKey(String email) {
        return buildKey(REGISTRATION_OTP_KIND, email);
    }

    private String buildLoginOtpCodeKey(String challengeToken) {
        return buildKey(LOGIN_OTP_CODE_KIND, challengeToken);
    }

    private String buildLoginOtpEmailKey(String challengeToken) {
        return buildKey(LOGIN_OTP_EMAIL_KIND, challengeToken);
    }

    private String buildResetKey(String hashedToken) {
        return buildKey(RESET_PASSWORD_KIND, hashedToken);
    }
}

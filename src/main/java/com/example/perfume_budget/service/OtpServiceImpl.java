package com.example.perfume_budget.service;


import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.exception.BadRequestException;
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
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private static final long LOGIN_OTP_EXPIRATION_MINUTES = 5;
    private static final long RESET_PASSWORD_TOKEN_EXPIRATION_MINUTES = 15;
    private static final String REGISTRATION_OTP_PREFIX = "otp:registration:";
    private static final String LOGIN_OTP_CODE_PREFIX = "otp:login:code:";
    private static final String LOGIN_OTP_EMAIL_PREFIX = "otp:login:email:";

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
        Object storedOtp = redisTemplate.opsForValue().get(buildLoginOtpCodeKey(challengeToken));
        if (storedOtp != null && storedOtp.equals(otp)) {
            redisTemplate.delete(buildLoginOtpCodeKey(challengeToken));
            redisTemplate.delete(buildLoginOtpEmailKey(challengeToken));
            return true;
        }
        return false;
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
        String resetPasswordToken = generatePasswordResetToken(email);
        ResetPasswordEvent event = new ResetPasswordEvent(email, fullName, resetPasswordToken);
        eventPublisher.publishEvent(event);
    }

    @Override
    public void invalidateResetPasswordToken(String passwordResetToken) {
        redisTemplate.delete(passwordResetToken);
    }

    @Override
    public boolean verifyRegistrationOtp(String email, String otp) {
        Object storedOtp = redisTemplate.opsForValue().get(buildRegistrationOtpKey(email));
        if (storedOtp != null && storedOtp.equals(otp) ) {
            redisTemplate.delete(buildRegistrationOtpKey(email));
            return true;
        }
        return false;
    }

    @Override
    public String getEmailFromLoginChallenge(String challengeToken) {
        Object storedEmail = redisTemplate.opsForValue().get(buildLoginOtpEmailKey(challengeToken));
        return storedEmail != null ? storedEmail.toString() : null;
    }

    @Override
    public boolean verifyResetPasswordToken(String passwordResetToken) {
        String storedToken =(String) redisTemplate.opsForValue().get(passwordResetToken);
        return storedToken != null;
    }

    @Override
    public String getEmailFromResetPasswordToken(String passwordResetToken) {
        return (String) redisTemplate.opsForValue().get(passwordResetToken);
    }


//    HELPER METHODS

    private String generateRegistrationOtp(String email) {
        String otp = generateOtp();
        redisTemplate.opsForValue().set(buildRegistrationOtpKey(email), otp, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        return otp;
    }

    private String generateLoginOtp(String challengeToken, String email) {
        String otp = generateOtp();
        redisTemplate.opsForValue().set(buildLoginOtpCodeKey(challengeToken), otp, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        redisTemplate.opsForValue().set(buildLoginOtpEmailKey(challengeToken), email, Duration.ofMinutes(LOGIN_OTP_EXPIRATION_MINUTES));
        return otp;
    }

    private String generateOtp() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
    }

    private String generateEncodedToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
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
        redisTemplate.opsForValue().set(hashedToken, email, Duration.ofMinutes(RESET_PASSWORD_TOKEN_EXPIRATION_MINUTES));
    }

    private String generatePasswordResetToken(String email){
        String hashedToken = hashToken(generateEncodedToken());
        saveResetPasswordToken(hashedToken, email);
        return hashedToken;
    }

    private String buildRegistrationOtpKey(String email) {
        return REGISTRATION_OTP_PREFIX + email;
    }

    private String buildLoginOtpCodeKey(String challengeToken) {
        return LOGIN_OTP_CODE_PREFIX + challengeToken;
    }

    private String buildLoginOtpEmailKey(String challengeToken) {
        return LOGIN_OTP_EMAIL_PREFIX + challengeToken;
    }
}

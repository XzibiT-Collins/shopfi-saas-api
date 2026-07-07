package com.example.perfume_budget.service;

import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OtpServiceImpl otpService;

    private final String email = "test@example.com";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void sendRegistrationOtp_Success() {
        otpService.sendRegistrationOtp(email);

        verify(valueOperations).set(eq("otp:registration:" + email), anyString(), any(Duration.class));
        verify(eventPublisher).publishEvent(any(UserRegistrationEvent.class));
    }

    @Test
    void initiateLoginOtp_Success() {
        String challengeToken = otpService.initiateLoginOtp(email);

        assertNotNull(challengeToken);
        verify(valueOperations).set(eq("otp:login:code:" + challengeToken), anyString(), any(Duration.class));
        verify(valueOperations).set(eq("otp:login:email:" + challengeToken), eq(email), any(Duration.class));
        verify(eventPublisher).publishEvent(any(LoginOtpEvent.class));
    }

    @Test
    void requestResetPasswordToken_Success() {
        otpService.requestResetPasswordToken(email, "Test User");

        verify(valueOperations).set(anyString(), eq(email), any(Duration.class));
        verify(eventPublisher).publishEvent(any(ResetPasswordEvent.class));
    }

    @Test
    void invalidateResetPasswordToken_Success() {
        String token = "token123";
        otpService.invalidateResetPasswordToken(token);
        verify(redisTemplate).delete(token);
    }

    @Test
    void verifyRegistrationOtp_Success() {
        String otp = "123456";
        when(valueOperations.get("otp:registration:" + email)).thenReturn(otp);

        boolean result = otpService.verifyRegistrationOtp(email, otp);

        assertTrue(result);
        verify(redisTemplate).delete("otp:registration:" + email);
    }

    @Test
    void verifyRegistrationOtp_Failure_WrongOtp() {
        when(valueOperations.get("otp:registration:" + email)).thenReturn("123456");

        boolean result = otpService.verifyRegistrationOtp(email, "654321");

        assertFalse(result);
        verify(redisTemplate, never()).delete(email);
    }

    @Test
    void verifyRegistrationOtp_Failure_NoStoredOtp() {
        when(valueOperations.get("otp:registration:" + email)).thenReturn(null);

        boolean result = otpService.verifyRegistrationOtp(email, "123456");

        assertFalse(result);
    }

    @Test
    void verifyLoginOtp_Success() {
        String challengeToken = "challenge-123";
        String otp = "123456";
        when(valueOperations.get("otp:login:code:" + challengeToken)).thenReturn(otp);

        boolean result = otpService.verifyLoginOtp(challengeToken, otp);

        assertTrue(result);
        verify(redisTemplate).delete("otp:login:code:" + challengeToken);
        verify(redisTemplate).delete("otp:login:email:" + challengeToken);
    }

    @Test
    void verifyLoginOtp_Failure_WrongOtp() {
        String challengeToken = "challenge-123";
        when(valueOperations.get("otp:login:code:" + challengeToken)).thenReturn("123456");

        boolean result = otpService.verifyLoginOtp(challengeToken, "654321");

        assertFalse(result);
        verify(redisTemplate, never()).delete("otp:login:code:" + challengeToken);
        verify(redisTemplate, never()).delete("otp:login:email:" + challengeToken);
    }

    @Test
    void resendLoginOtp_Success() {
        String challengeToken = "challenge-123";
        when(valueOperations.get("otp:login:email:" + challengeToken)).thenReturn(email);

        otpService.resendLoginOtp(challengeToken);

        verify(valueOperations).set(eq("otp:login:code:" + challengeToken), anyString(), any(Duration.class));
        verify(valueOperations).set(eq("otp:login:email:" + challengeToken), eq(email), any(Duration.class));
        verify(eventPublisher).publishEvent(any(LoginOtpEvent.class));
    }

    @Test
    void resendLoginOtp_Failure_InvalidChallenge() {
        String challengeToken = "challenge-123";
        when(valueOperations.get("otp:login:email:" + challengeToken)).thenReturn(null);

        assertThrows(BadRequestException.class, () -> otpService.resendLoginOtp(challengeToken));
    }

    @Test
    void getEmailFromLoginChallenge_Success() {
        String challengeToken = "challenge-123";
        when(valueOperations.get("otp:login:email:" + challengeToken)).thenReturn(email);

        String result = otpService.getEmailFromLoginChallenge(challengeToken);

        assertEquals(email, result);
    }

    @Test
    void verifyResetPasswordToken_Success() {
        String token = "valid-token";
        when(valueOperations.get(token)).thenReturn(email);

        boolean result = otpService.verifyResetPasswordToken(token);

        assertTrue(result);
    }

    @Test
    void verifyResetPasswordToken_Failure() {
        String token = "invalid-token";
        when(valueOperations.get(token)).thenReturn(null);

        boolean result = otpService.verifyResetPasswordToken(token);

        assertFalse(result);
    }

    @Test
    void getEmailFromResetPasswordToken_Success() {
        String token = "valid-token";
        when(valueOperations.get(token)).thenReturn(email);

        String result = otpService.getEmailFromResetPasswordToken(token);

        assertEquals(email, result);
    }
}

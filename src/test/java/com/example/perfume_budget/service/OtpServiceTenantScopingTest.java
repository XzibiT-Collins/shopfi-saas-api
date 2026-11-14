package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** TENANT-09: every OTP / reset-token Redis key is scoped by tenant slug. */
@SuppressWarnings("unchecked")
class OtpServiceTenantScopingTest {

    private final Map<String, Object> store = new HashMap<>();
    private RedisTemplate<String, Object> redis;
    private ApplicationEventPublisher publisher;
    private OtpServiceImpl service;

    @BeforeEach
    void setUp() {
        redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), any(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        service = new OtpServiceImpl(redis, publisher);
    }

    @AfterEach
    void clear() {
        TenantContext.set(null);
    }

    private static void as(String slug) {
        TenantContext.set(TenantIdentity.tenant(slug, "tenant_" + slug));
    }


    @Test
    void allOtpKeysCarryTenantSlug() {
        as("acme");
        service.sendRegistrationOtp("a@x.com");
        String challenge = service.initiateLoginOtp("a@x.com");
        service.requestResetPasswordToken("a@x.com", "A");

        assertTrue(store.containsKey("otp:registration:acme:a@x.com"));
        assertTrue(store.containsKey("otp:login:code:acme:" + challenge));
        assertTrue(store.containsKey("otp:login:email:acme:" + challenge));
        assertEquals(1, store.keySet().stream().filter(k -> k.startsWith("otp:reset:acme:")).count());
        assertTrue(store.keySet().stream().allMatch(k -> k.startsWith("otp:") && k.contains(":acme:")));
    }

    @Test
    void loginChallengeIssuedOnAcmeIsUnknownOnBravo() {
        as("acme");
        String challenge = service.initiateLoginOtp("a@x.com");
        String otp = (String) store.get("otp:login:code:acme:" + challenge);

        as("bravo");
        assertNull(service.getEmailFromLoginChallenge(challenge));
        assertFalse(service.verifyLoginOtp(challenge, otp));
        assertThrows(BadRequestException.class, () -> service.resendLoginOtp(challenge));

        as("acme");
        assertEquals("a@x.com", service.getEmailFromLoginChallenge(challenge));
        assertTrue(service.verifyLoginOtp(challenge, otp));
    }

    @Test
    void registrationOtpsForSameEmailDoNotCollide() {
        as("acme");
        service.sendRegistrationOtp("same@x.com");
        String acmeOtp = (String) store.get("otp:registration:acme:same@x.com");
        as("bravo");
        service.sendRegistrationOtp("same@x.com");
        String bravoOtp = (String) store.get("otp:registration:bravo:same@x.com");

        as("acme");
        assertTrue(service.verifyRegistrationOtp("same@x.com", acmeOtp));
        as("bravo");
        assertTrue(service.verifyRegistrationOtp("same@x.com", bravoOtp));
    }

    @Test
    void resetTokenIsTenantScoped() {
        as("acme");
        service.requestResetPasswordToken("a@x.com", "A");
        String token = store.keySet().stream().filter(k -> k.startsWith("otp:reset:acme:"))
                .findFirst().orElseThrow().substring("otp:reset:acme:".length());

        as("bravo");
        assertFalse(service.verifyResetPasswordToken(token));
        assertNull(service.getEmailFromResetPasswordToken(token));
        service.invalidateResetPasswordToken(token);

        as("acme");
        assertTrue(service.verifyResetPasswordToken(token));
        service.invalidateResetPasswordToken(token);
        assertFalse(service.verifyResetPasswordToken(token));
    }

    @Test
    void platformScopeUsesPlatformMarker() {
        TenantContext.set(TenantIdentity.platform());
        service.sendRegistrationOtp("p@x.com");
        assertTrue(store.containsKey("otp:registration:__platform__:p@x.com"));
    }
}

package com.example.perfume_budget.service;

import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.support.FakeRedis;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** AUTH-01 AC1/AC3: tenant-scoped OTP/reset keys, attempt caps, SecureRandom-style codes, decoy OTP. */
@ExtendWith(MockitoExtension.class)
class OtpServiceHardeningTest {

    @Mock RedisTemplate<String, Object> redis;
    @Mock ApplicationEventPublisher events;
    FakeRedis fake;
    OtpServiceImpl otp;

    @BeforeEach
    void setUp() {
        fake = FakeRedis.wire(redis);
        otp = new OtpServiceImpl(redis, events);
        acme();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void acme() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
    }

    private static void bravo() {
        TenantContext.set(TenantIdentity.tenant("bravo", "tenant_bravo"));
    }

    private String lastRegistrationOtp() {
        ArgumentCaptor<UserRegistrationEvent> c = ArgumentCaptor.forClass(UserRegistrationEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(c.capture());
        return c.getValue().otp();
    }

    @Test
    void registrationOtp_sameEmailOnTwoTenants_isIndependent() {
        otp.sendRegistrationOtp("u@x.test");
        String acmeCode = lastRegistrationOtp();
        bravo();
        assertThat(otp.verifyRegistrationOtp("u@x.test", acmeCode)).as("acme code must not verify on bravo").isFalse();
        acme();
        assertThat(otp.verifyRegistrationOtp("u@x.test", acmeCode)).isTrue();
        assertThat(fake.store.keySet()).noneMatch(k -> k.equals("otp:registration:u@x.test"));
    }

    @Test
    void loginChallenge_issuedOnAcme_cannotBeRedeemedOnBravo() {
        String challenge = otp.initiateLoginOtp("u@x.test");
        ArgumentCaptor<LoginOtpEvent> c = ArgumentCaptor.forClass(LoginOtpEvent.class);
        verify(events).publishEvent(c.capture());
        String code = c.getValue().otp();
        bravo();
        assertThat(otp.getEmailFromLoginChallenge(challenge)).isNull();
        assertThat(otp.verifyLoginOtp(challenge, code)).isFalse();
        acme();
        assertThat(otp.getEmailFromLoginChallenge(challenge)).isEqualTo("u@x.test");
        assertThat(otp.verifyLoginOtp(challenge, code)).isTrue();
    }

    @Test
    void resetToken_isOnlyUsableOnTheTenantItWasIssuedFor() {
        otp.requestResetPasswordToken("u@x.test", "U");
        ArgumentCaptor<ResetPasswordEvent> c = ArgumentCaptor.forClass(ResetPasswordEvent.class);
        verify(events).publishEvent(c.capture());
        ResetPasswordEvent ev = c.getValue();
        assertThat(ev.tenantSlug()).isEqualTo("acme");
        bravo();
        assertThat(otp.verifyResetPasswordToken(ev.token())).isFalse();
        assertThat(otp.getEmailFromResetPasswordToken(ev.token())).isNull();
        otp.invalidateResetPasswordToken(ev.token()); // bravo cannot burn acme's token either
        acme();
        assertThat(otp.verifyResetPasswordToken(ev.token())).isTrue();
        assertThat(fake.store.keySet()).allMatch(k -> k.contains(":acme:"));
        otp.invalidateResetPasswordToken(ev.token());
        assertThat(otp.verifyResetPasswordToken(ev.token())).isFalse();
    }

    @Test
    void otpOperationsWithoutTenant_failClosed() {
        TenantContext.clear();
        assertThatThrownBy(() -> otp.sendRegistrationOtp("u@x.test")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> otp.verifyResetPasswordToken("abc")).isInstanceOf(IllegalStateException.class);
        TenantContext.set(TenantIdentity.platform());
        assertThatThrownBy(() -> otp.requestResetPasswordToken("u@x.test", "U")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registrationOtp_isDestroyedAfterTheAttemptCap_evenForTheCorrectCodeAfterwards() {
        otp.sendRegistrationOtp("u@x.test");
        String code = lastRegistrationOtp();
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < OtpServiceImpl.MAX_OTP_ATTEMPTS; i++) {
            assertThat(otp.verifyRegistrationOtp("u@x.test", wrong)).isFalse();
        }
        assertThat(otp.verifyRegistrationOtp("u@x.test", code)).as("code burned after the cap").isFalse();
    }

    @Test
    void registrationOtp_correctCodeWithinTheCapStillWorks_andAFreshCodeResetsTheBudget() {
        otp.sendRegistrationOtp("u@x.test");
        String code = lastRegistrationOtp();
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < OtpServiceImpl.MAX_OTP_ATTEMPTS - 1; i++) {
            assertThat(otp.verifyRegistrationOtp("u@x.test", wrong)).isFalse();
        }
        assertThat(otp.verifyRegistrationOtp("u@x.test", code)).isTrue();
        // new code, new budget
        org.mockito.Mockito.clearInvocations(events);
        otp.sendRegistrationOtp("u@x.test");
        String code2 = lastRegistrationOtp();
        String wrong2 = code2.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < OtpServiceImpl.MAX_OTP_ATTEMPTS - 1; i++) {
            otp.verifyRegistrationOtp("u@x.test", wrong2);
        }
        assertThat(otp.verifyRegistrationOtp("u@x.test", code2)).isTrue();
    }

    @Test
    void loginOtp_attemptCapKillsTheWholeChallenge() {
        String challenge = otp.initiateLoginOtp("u@x.test");
        ArgumentCaptor<LoginOtpEvent> c = ArgumentCaptor.forClass(LoginOtpEvent.class);
        verify(events).publishEvent(c.capture());
        String code = c.getValue().otp();
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < OtpServiceImpl.MAX_OTP_ATTEMPTS; i++) {
            assertThat(otp.verifyLoginOtp(challenge, wrong)).isFalse();
        }
        assertThat(otp.verifyLoginOtp(challenge, code)).isFalse();
        assertThat(otp.getEmailFromLoginChallenge(challenge)).as("challenge destroyed").isNull();
    }

    @Test
    void decoyOtp_deliversNothing_butWritesLikeARealOne() {
        otp.issueDecoyRegistrationOtp("ghost@x.test");
        verify(events, never()).publishEvent(any(Object.class));
        assertThat(fake.store).containsKey("otp:registration:acme:ghost@x.test");
    }

    @Test
    void generatedCodes_areSixDigits_andNotAConstant() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            org.mockito.Mockito.clearInvocations(events);
            otp.sendRegistrationOtp("u" + i + "@x.test");
            String code = lastRegistrationOtp();
            assertThat(code).matches("[1-9][0-9]{5}");
            seen.add(code);
        }
        assertThat(seen.size()).isGreaterThan(30);
    }
}

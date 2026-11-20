package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AuthRateLimiter limiter() {
        return new AuthRateLimiter(5, 3600, 2, 3600, 100);
    }

    @Test
    void perIpAndEmail_blocksTheThirdRequest_caseAndWhitespaceInsensitive_withRetryAfter() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkResendOtp("1.1.1.1", "a@x.test");
        l.checkResendOtp("1.1.1.1", " A@X.test ");
        assertThatThrownBy(() -> l.checkResendOtp("1.1.1.1", "a@x.TEST"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getRetryAfterSeconds()).isPositive());
    }

    /** AUTH-02 AC1: a stranger spending the email budget from one address does not lock the victim out from theirs. */
    @Test
    void anotherAddressKeepsItsOwnEmailBudget_untilTheCeiling() {
        AuthRateLimiter l = new AuthRateLimiter(50, 3600, 2, 3600, 100, 5, 10, 3, 10);
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkResendOtp("6.6.6.6", "victim@x.test");
        l.checkResendOtp("6.6.6.6", "victim@x.test");
        assertThatThrownBy(() -> l.checkResendOtp("6.6.6.6", "victim@x.test")).isInstanceOf(RateLimitExceededException.class);
        l.checkResendOtp("2.2.2.2", "victim@x.test");   // the victim's own address still works
        l.checkResendOtp("2.2.2.2", "victim@x.test");
        // the ceiling (5 per tenant+email) bounds mail-bombing from many addresses: 2 + 2 spent, 1 left
        l.checkResendOtp("3.3.3.3", "victim@x.test");
        assertThatThrownBy(() -> l.checkResendOtp("4.4.4.4", "victim@x.test")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void forgotPasswordAndLoginOtpResendHaveTheirOwnBudgets() {
        AuthRateLimiter l = new AuthRateLimiter(50, 3600, 2, 3600, 100, 10, 50, 2, 2);
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkForgotPassword("1.1.1.1", "a@x.test");
        l.checkForgotPassword("1.1.1.1", "a@x.test");
        assertThatThrownBy(() -> l.checkForgotPassword("1.1.1.1", "a@x.test")).isInstanceOf(RateLimitExceededException.class);
        l.checkResendOtp("1.1.1.1", "a@x.test"); // a different endpoint, a different budget
        l.checkResendLoginOtp("1.1.1.1");
        l.checkResendLoginOtp("1.1.1.1");
        assertThatThrownBy(() -> l.checkResendLoginOtp("1.1.1.1")).isInstanceOf(RateLimitExceededException.class);
        l.checkResendLoginOtp("1.1.1.2");
    }

    @Test
    void perEmail_isScopedPerTenant() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkResendOtp("1.1.1.1", "a@x.test");
        l.checkResendOtp("1.1.1.1", "a@x.test");
        TenantContext.set(TenantIdentity.tenant("bravo", "tenant_bravo"));
        l.checkResendOtp("1.1.1.2", "a@x.test"); // other tenant, own budget
    }

    @Test
    void perIp_blocksTheSixthRequestAcrossDifferentEmails() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        for (int i = 0; i < 5; i++) {
            l.checkResendOtp("9.9.9.9", "u" + i + "@x.test");
        }
        assertThatThrownBy(() -> l.checkResendOtp("9.9.9.9", "u99@x.test")).isInstanceOf(RateLimitExceededException.class);
        l.checkResendOtp("9.9.9.8", "u99@x.test"); // another IP is unaffected
    }
}
